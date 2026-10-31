package com.ose.multitimer

import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.Executor

/**
 * Coordinates profile import decisions without depending on Android widgets.
 *
 * The caller supplies the worker executor and the one storage operation that
 * appends a validated batch. The coordinator never changes a clock or starts a
 * timer service. State listeners may be called from the initiating thread or
 * the worker thread; Android callers must marshal view updates to the main
 * thread and ignore callbacks whose requestId is older than the displayed one.
 *
 * A load is decoded completely before Preview is published. Confirmation is
 * the only path that turns portable snapshots into local profiles and calls
 * storage. Cancelling Loading or Preview invalidates that request's generation
 * so a late decode result cannot restore a cancelled preview. Once Saving has
 * begun, neither cancellation nor a replacement load is accepted because the
 * persistent operation cannot honestly be rolled back.
 *
 * @param worker executes decoding and profile persistence away from a UI thread
 * @param appendProfiles appends all supplied profiles as one logical store update
 * @param decodeBundle validates bytes and returns detached portable profiles
 * @param onStateChanged observes immutable state snapshots; do not block or
 *                      throw from this callback
 */
class ProfileImportCoordinator(
    private val worker: Executor,
    private val appendProfiles: (List<BeepProfile>) -> Unit,
    private val decodeBundle: (ByteArray) -> List<SharedProfile> = ProfileBundleCodec::decode,
    private val onStateChanged: (State) -> Unit = {},
) {
    /** Immutable states exposed to a screen or to deterministic unit tests. */
    sealed class State(open val requestId: Long) {
        /** No import operation is currently active. */
        data class Idle(override val requestId: Long = 0L) : State(requestId)

        /** The worker is validating all bytes from one selected document. */
        data class Loading(override val requestId: Long) : State(requestId)

        /** A whole valid bundle is ready; selection is by row index, not name. */
        data class Preview(
            override val requestId: Long,
            val profiles: List<SharedProfile>,
            val selectedIndices: Set<Int>,
        ) : State(requestId) {
            /** Number of selected rows, including rows with duplicate names. */
            val selectedCount: Int
                get() = selectedIndices.size
        }

        /** A non-cancellable batch write has started. */
        data class Saving(override val requestId: Long, val profileCount: Int) : State(requestId)

        /** The store reported that the complete batch was accepted. */
        data class Success(override val requestId: Long, val importedCount: Int) : State(requestId)

        /** Decoding or persistence failed; the message is suitable for display. */
        data class Error(override val requestId: Long, val message: String) : State(requestId)
    }

    private val lock = Any()
    private var latestRequestId = 0L

    @Volatile
    private var currentState: State = State.Idle()

    /** Returns the latest immutable state snapshot. */
    val state: State
        get() = synchronized(lock) { currentState }

    /**
     * Starts decoding a copied document and returns its generation identifier.
     *
     * Starting another load supersedes an older decode. A load is rejected
     * while a store write is active because that write cannot be cancelled.
     * The bytes are copied before asynchronous work so the caller may safely
     * reuse or modify its original array after this method returns.
     *
     * @throws IllegalStateException if a profile batch is already being saved
     */
    fun load(documentBytes: ByteArray): Long {
        // Do not duplicate an oversized attacker-controlled file before the
        // codec gets a chance to enforce its own bound.
        val isOversized = documentBytes.size > ProfileSharingLimits.MAX_DOCUMENT_BYTES
        val safeBytes = if (isOversized) null else documentBytes.copyOf()
        val loading = synchronized(lock) {
            check(currentState !is State.Saving) {
                "A profile import is already being saved"
            }
            val requestId = nextRequestIdLocked()
            State.Loading(requestId).also { currentState = it }
        }
        onStateChanged(loading)

        try {
            worker.execute {
                try {
                    require(!isOversized) {
                        "Profile bundle exceeds ${ProfileSharingLimits.MAX_DOCUMENT_BYTES} bytes"
                    }
                    val decoded = decodeBundle(checkNotNull(safeBytes))
                    require(decoded.isNotEmpty()) { "The profile bundle contains no profiles" }
                    require(decoded.size <= ProfileSharingLimits.MAX_PROFILE_COUNT) {
                        "The profile bundle contains too many profiles"
                    }
                    finishLoading(loading.requestId, decoded)
                } catch (failure: Exception) {
                    finishLoadingWithError(loading.requestId, failure)
                }
            }
        } catch (failure: RuntimeException) {
            // Executors can reject work during shutdown. Surface that as an
            // import error rather than leaving the screen permanently Loading.
            finishLoadingWithError(loading.requestId, failure)
        }
        return loading.requestId
    }

    /**
     * Selects or deselects one preview row.
     *
     * Row indices keep equal display names independent. Returns false when no
     * preview exists or the index is outside its current list.
     */
    fun setSelected(index: Int, selected: Boolean): Boolean {
        val next = synchronized(lock) {
            val preview = currentState as? State.Preview ?: return false
            if (index !in preview.profiles.indices) {
                return false
            }
            val wasSelected = index in preview.selectedIndices
            if (wasSelected == selected) {
                return true
            }

            val updatedIndices = LinkedHashSet(preview.selectedIndices)
            if (selected) {
                updatedIndices.add(index)
            } else {
                updatedIndices.remove(index)
            }
            State.Preview(
                preview.requestId,
                preview.profiles,
                Collections.unmodifiableSet(updatedIndices),
            ).also { currentState = it }
        }
        onStateChanged(next)
        return true
    }

    /**
     * Confirms exactly the currently selected rows and schedules one batch write.
     *
     * Fresh Android profile and event IDs are generated only after this explicit
     * call. Returns false when there is no preview or nothing is selected. A
     * second call while saving cannot issue another write.
     */
    fun confirm(): Boolean {
        val (saving, selectedProfiles) = synchronized(lock) {
            val preview = currentState as? State.Preview ?: return false
            if (preview.selectedIndices.isEmpty()) {
                return false
            }
            val selected = preview.profiles.filterIndexed { index, _ ->
                index in preview.selectedIndices
            }
            val nextState = State.Saving(preview.requestId, selected.size)
            currentState = nextState
            nextState to selected
        }
        onStateChanged(saving)

        try {
            worker.execute {
                try {
                    // Materialize all values before calling storage. If any
                    // profile fails validation, no partial store call is made.
                    val materialized = selectedProfiles.map { it.toNewProfile() }
                    val ids = HashSet<String>()
                    for (profile in materialized) {
                        require(profile.reusable && profile.ownerClockId == null) {
                            "Imported profiles must be reusable and have no clock owner"
                        }
                        require(ids.add(profile.id)) {
                            "Imported profiles contain a duplicate generated ID"
                        }
                    }
                    appendProfiles(Collections.unmodifiableList(materialized))
                    finishSaving(saving.requestId, materialized.size)
                } catch (failure: Exception) {
                    finishSavingWithError(saving.requestId, failure)
                }
            }
        } catch (failure: RuntimeException) {
            finishSavingWithError(saving.requestId, failure)
        }
        return true
    }

    /**
     * Cancels a decode or an unconfirmed preview without writing anything.
     *
     * A request generation is advanced before Idle is published, so any worker
     * result already in flight is ignored. Returns false once Saving has begun,
     * after completion, or when there is no cancellable import.
     */
    fun cancel(): Boolean {
        val idle = synchronized(lock) {
            if (currentState !is State.Loading && currentState !is State.Preview) {
                return false
            }
            State.Idle(nextRequestIdLocked()).also { currentState = it }
        }
        onStateChanged(idle)
        return true
    }

    /** Completes a decode only if its request is still the active Loading state. */
    private fun finishLoading(requestId: Long, decoded: List<SharedProfile>) {
        val preview = synchronized(lock) {
            val loading = currentState as? State.Loading
            if (loading?.requestId != requestId) {
                return
            }
            val stableProfiles = Collections.unmodifiableList(ArrayList(decoded))
            val selected = LinkedHashSet<Int>().apply {
                for (index in stableProfiles.indices) {
                    add(index)
                }
            }
            State.Preview(
                requestId,
                stableProfiles,
                Collections.unmodifiableSet(selected),
            ).also { currentState = it }
        }
        onStateChanged(preview)
    }

    /** Reports a decode or executor failure only while its load is still current. */
    private fun finishLoadingWithError(requestId: Long, failure: Exception) {
        val error = synchronized(lock) {
            val loading = currentState as? State.Loading
            if (loading?.requestId != requestId) {
                return
            }
            State.Error(requestId, readableMessage(failure)).also { currentState = it }
        }
        onStateChanged(error)
    }

    /** Publishes success only for the batch that currently owns the Saving state. */
    private fun finishSaving(requestId: Long, importedCount: Int) {
        val success = synchronized(lock) {
            val saving = currentState as? State.Saving
            if (saving?.requestId != requestId) {
                return
            }
            State.Success(requestId, importedCount).also { currentState = it }
        }
        onStateChanged(success)
    }

    /** A failed writer never becomes Success, even if conversion succeeded. */
    private fun finishSavingWithError(requestId: Long, failure: Exception) {
        val error = synchronized(lock) {
            val saving = currentState as? State.Saving
            if (saving?.requestId != requestId) {
                return
            }
            State.Error(requestId, readableMessage(failure)).also { currentState = it }
        }
        onStateChanged(error)
    }

    /** Advances the token that invalidates work from earlier loads. Must hold lock. */
    private fun nextRequestIdLocked(): Long {
        check(latestRequestId < Long.MAX_VALUE) { "Import request IDs are exhausted" }
        latestRequestId += 1L
        return latestRequestId
    }

    /** Replaces an empty platform exception message with text the user can read. */
    private fun readableMessage(failure: Exception): String {
        return failure.message?.takeIf(String::isNotBlank)
            ?: "The profile import could not be completed"
    }
}
