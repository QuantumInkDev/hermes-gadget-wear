package dev.quantumink.hermesgadget.protocol.relay.voice

import dev.quantumink.hermesgadget.protocol.relay.RequestId
import dev.quantumink.hermesgadget.protocol.relay.state.AggregateShape
import dev.quantumink.hermesgadget.protocol.relay.state.RelayAuthority
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateRestore
import dev.quantumink.hermesgadget.protocol.relay.state.RelayStateSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.RequestIds
import dev.quantumink.hermesgadget.protocol.relay.state.RestoreNotice
import dev.quantumink.hermesgadget.protocol.relay.state.RestoreRefusal
import dev.quantumink.hermesgadget.protocol.relay.state.RestoreResult
import dev.quantumink.hermesgadget.protocol.relay.state.RestoredState
import dev.quantumink.hermesgadget.protocol.relay.state.SnapshotBuild
import dev.quantumink.hermesgadget.protocol.relay.state.StateTestSupport
import dev.quantumink.hermesgadget.protocol.relay.state.TurnLedger
import dev.quantumink.hermesgadget.protocol.relay.state.TurnLedgerSnapshot
import dev.quantumink.hermesgadget.protocol.relay.state.TurnPhase
import dev.quantumink.hermesgadget.protocol.relay.state.UncertainReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The capture section of the aggregate snapshot (version 2): ownership, restore validation, TooMany, and the preserving v1 migration. */
class VoiceSnapshotTest {
    private fun snapshotOf(v: VoiceHarness): RelayStateSnapshot = (
        RelayStateSnapshot.of(
            v.h.ledger,
            emptyList(),
            emptyList(),
            v.captures
        ) as SnapshotBuild.Built
        ).snapshot

    private fun restore(
        snapshot: RelayStateSnapshot,
        v: VoiceHarness
    ): RestoreResult<RestoredState> =
        RelayStateRestore.restore(snapshot, v.h.now(), RelayAuthority.fresh())

    private fun restored(snapshot: RelayStateSnapshot, v: VoiceHarness): RestoredState =
        (restore(snapshot, v) as RestoreResult.Restored).value

    private fun refused(result: RestoreResult<RestoredState>): RestoreResult.Refused =
        result as RestoreResult.Refused

    /** A ledger holding a record in every client state and every ending. */
    private fun everyState(): Triple<
        VoiceHarness,
        Map<String, CaptureKey>,
        dev.quantumink.hermesgadget.protocol.relay.state.BindingToken
        > {
        val v = VoiceHarness()
        val token = v.h.attach()
        val keys = LinkedHashMap<String, CaptureKey>()
        keys["recording"] = v.begin(token)
        keys["finishing"] = v.released(token)
        keys["awaiting"] =
            v.released(token).also {
                v.onResult(
                    it,
                    v.result(it, CaptureState.TRANSCRIBING, provider = VoiceProvider.IN_FLIGHT)
                )
            }
        val linkedKey = v.released(token)
        val result = v.transcribed(linkedKey)
        v.onResult(linkedKey, result)
        val step = VoiceAdmission.admit(
            v.h.ledger,
            v.captures,
            linkedKey,
            result,
            v.h.authority,
            v.h.now(),
            v.h.entropy,
            v.shape
        )
        v.h.ledger = step.turns
        v.captures = step.captures
        keys["admitting"] = linkedKey
        val draftKey = v.released(token)
        val draftResult = v.transcribed(draftKey)
        v.onResult(draftKey, draftResult)
        v.apply(v.captures.withdrawIntents(draftKey.conversation))
        v.apply(v.captures.offerDraft(v.h.ledger, draftKey, draftResult))
        keys["draft"] = draftKey
        val cancelled = v.begin(token)
        v.apply(v.captures.cancel(cancelled, v.h.now()))
        keys["cancelled"] = cancelled
        val lost = v.begin(token)
        v.apply(v.captures.lose(lost, v.h.now()))
        keys["lost"] = lost
        for ((name, state) in listOf(
            "empty" to CaptureState.EMPTY,
            "unknown" to CaptureState.UNKNOWN,
            "revoked" to CaptureState.REVOKED
        )) {
            val k = v.released(token)
            val provider = if (state ==
                CaptureState.UNKNOWN
            ) {
                VoiceProvider.UNKNOWN
            } else {
                VoiceProvider.COMPLETED
            }
            v.onResult(
                k,
                v.result(
                    k,
                    state,
                    provider = provider,
                    source = if (state ==
                        CaptureState.EMPTY
                    ) {
                        TranscriptSource.CONFIGURED
                    } else {
                        null
                    }
                )
            )
            keys["ended-$name"] = k
        }
        val failed = v.released(token)
        v.onResult(
            failed,
            v.result(failed, CaptureState.FAILED, CaptureFailure.TOO_SHORT, sealed = null)
        )
        keys["ended-pre-seal"] = failed
        val gone = v.released(token)
        v.onResult(
            gone,
            v.result(
                gone,
                CaptureState.FAILED,
                CaptureFailure.PROVIDER_FAILED,
                VoiceProvider.FAILED_AFTER_SEND
            )
        )
        keys["ended-failed"] = gone
        return Triple(v, keys, token)
    }

    @Test fun everyStateAndEndingSurvivesARoundTripWithoutInventingOrDroppingAnything() {
        val (v, keys, _) = everyState()
        val snapshot = snapshotOf(v)
        assertEquals(RelayStateSnapshot.VERSION, snapshot.version)
        assertEquals(2, snapshot.version)
        assertEquals(CaptureLedgerSnapshot.VERSION, snapshot.captures.version)
        assertEquals(v.captures.size, snapshot.captures.records.size)
        val result = restore(snapshot, v) as RestoreResult.Restored
        val back = result.value.captures
        assertEquals(v.captures.size, back.size)
        for ((name, key) in keys) {
            val a = v.entry(key)
            val b = back.entry(key)!!
            assertEquals(name, a.createdAtMs, b.createdAtMs)
            assertEquals(name, a.lifecycle, b.lifecycle)
            assertEquals(name, a.linkedRequest, b.linkedRequest)
            assertEquals(name, a.provider, b.provider)
            assertEquals(name, a.end, b.end)
            assertEquals(name, a.failure, b.failure)
            assertEquals(name, a.terminalAtMs, b.terminalAtMs)
            assertEquals(name, a.billing, b.billing)
            assertNull("$name: a restore never revives a transient intent", back.intentOf(key))
        }
        assertEquals(setOf(RestoreNotice.CAPTURE_RECORDINGS_LOST), result.notices.toSet())
        // the same snapshot value also carries the prepared turn of the linked capture, unchanged
        val turn = result.value.ledger.entries.single()
        assertNotEquals(keys.getValue("admitting").captureId.value, turn.key.requestId.value)
        assertEquals("hello voice", turn.text)
    }

    @Test fun aRestoredRecordingHasNoAudioAndOnlyBecomesLostByAnExplicitReconcile() {
        val v = VoiceHarness()
        val token = v.h.attach()
        val key = v.begin(token)
        val back = restored(snapshotOf(v), v)
        assertEquals(CaptureLifecycle.RECORDING, back.captures.entry(key)!!.lifecycle)
        val fresh = v.h.attach()
        val authority = v.h.authority
        val plan = back.captures.planStart(
            back.ledger,
            authority,
            fresh,
            key,
            v.stream,
            CaptureFormat.PCM16
        )
        assertEquals(PlanRefusal.AUDIO_NOT_LIVE, (plan.result as CapturePlan.Refused).reason)
        assertEquals(
            PlanRefusal.AUDIO_NOT_LIVE,
            (
                back.captures.planFinish(
                    back.ledger,
                    authority,
                    fresh,
                    key,
                    v.stream,
                    v.counts(),
                    v.h.now(),
                    v.shape
                ).result as CapturePlan.Refused
                ).reason
        )
        val step = back.captures.reconcileRecordings(v.h.now())
        assertEquals(1, step.result)
        assertEquals(CaptureLifecycle.LOST, step.ledger.entry(key)!!.lifecycle)
        assertEquals(listOf<CaptureEffect>(CaptureEffect.PersistAggregate), step.effects)
    }

    // -- the preserving migration ----------------------------------------------------------------------------------------------------

    private fun turnsInEveryUnresolvedShape(): Pair<
        VoiceHarness,
        List<dev.quantumink.hermesgadget.protocol.relay.state.TurnKey>
        > {
        val v = VoiceHarness()
        val keys = ArrayList<dev.quantumink.hermesgadget.protocol.relay.state.TurnKey>()
        val prepared = v.h.prepare("never sent")
        keys.add(prepared)
        val uncertain = v.h.prepare("timed out")
        val t1 = v.h.attach()
        v.h.timeout(v.h.planned(v.h.submit(uncertain, t1)))
        keys.add(uncertain)
        val expired = v.h.prepare("expired reported")
        val t2 = v.h.attach()
        v.h.fail(v.h.planned(v.h.submit(expired, t2)), "expired")
        assertEquals(UncertainReason.EXPIRED_REPORTED, v.h.entry(expired).uncertainty)
        keys.add(expired)
        val done = v.h.prepare("completed")
        val t3 = v.h.attach()
        v.h.reply(
            v.h.planned(v.h.submit(done, t3)),
            v.h.receipt(
                done,
                dev.quantumink.hermesgadget.protocol.relay.ReceiptStatus.TERMINAL,
                1,
                "completed"
            )
        )
        keys.add(done)
        return v to keys
    }

    @Test fun aVersionOneSnapshotRestoresEveryTurnIncludingUncertainAndExpiredReported() {
        val (v, keys) = turnsInEveryUnresolvedShape()
        val ledgerSnapshot: TurnLedgerSnapshot = v.h.ledger.snapshot()
        val legacy =
            RelayStateSnapshot(
                RelayStateSnapshot.LEGACY_VERSION,
                ledgerSnapshot,
                emptyList(),
                emptyList()
            )
        val current =
            RelayStateSnapshot(RelayStateSnapshot.VERSION, ledgerSnapshot, emptyList(), emptyList())
        val fromLegacy = restored(legacy, v)
        val fromCurrent = restored(current, v)
        assertEquals(4, fromLegacy.ledger.size)
        for (key in keys) {
            val a = fromLegacy.ledger.entry(key)!!
            val b = fromCurrent.ledger.entry(key)!!
            val original = v.h.ledger.entry(key)!!
            assertEquals(original.text, a.text)
            assertEquals(original.expiresAtMs, a.expiresAtMs)
            assertEquals(original.sent, a.sent)
            assertEquals(original.facts, a.facts)
            assertEquals(original.fingerprint, a.fingerprint)
            assertEquals(b.phase, a.phase)
            assertEquals(b.uncertainty, a.uncertainty)
            assertEquals(b.status, a.status)
        }
        // the unresolved turns come back unresolved (process loss), never closed, never resent by themselves
        assertEquals(UncertainReason.PROCESS_LOSS, fromLegacy.ledger.entry(keys[1])!!.uncertainty)
        assertEquals(UncertainReason.PROCESS_LOSS, fromLegacy.ledger.entry(keys[2])!!.uncertainty)
        assertEquals(TurnPhase.UNCERTAIN, fromLegacy.ledger.entry(keys[2])!!.phase)
        assertEquals(TurnPhase.PREPARED, fromLegacy.ledger.entry(keys[0])!!.phase)
        assertEquals(TurnPhase.TERMINAL, fromLegacy.ledger.entry(keys[3])!!.phase)
        assertEquals("a legacy snapshot has no captures", 0, fromLegacy.captures.size)
        // writing the migrated state back is a version 2 snapshot of the very same turns
        val rewritten = (
            RelayStateSnapshot.of(
                fromLegacy.ledger,
                emptyList(),
                emptyList(),
                fromLegacy.captures
            ) as SnapshotBuild.Built
            ).snapshot
        assertEquals(2, rewritten.version)
        assertEquals(fromLegacy.ledger.snapshot().turns.size, rewritten.ledger.turns.size)
        assertTrue(restore(rewritten, v) is RestoreResult.Restored)
    }

    @Test fun versionsAndSectionVersionsAreCheckedAndALegacySnapshotCannotCarryCaptures() {
        val v = VoiceHarness()
        val token = v.h.attach()
        v.begin(token)
        val ledger = v.h.ledger.snapshot()
        val captures = v.captures.snapshot()
        assertEquals(
            RestoreRefusal.VERSION,
            refused(
                restore(RelayStateSnapshot(3, ledger, emptyList(), emptyList(), captures), v)
            ).reason
        )
        assertEquals(
            RestoreRefusal.VERSION,
            refused(restore(RelayStateSnapshot(0, ledger, emptyList(), emptyList()), v)).reason
        )
        val legacyWithCaptures =
            refused(restore(RelayStateSnapshot(1, ledger, emptyList(), emptyList(), captures), v))
        assertEquals(RestoreRefusal.INCONSISTENT, legacyWithCaptures.reason)
        assertEquals("captures", legacyWithCaptures.path)
        val future =
            refused(
                restore(
                    RelayStateSnapshot(
                        2,
                        ledger,
                        emptyList(),
                        emptyList(),
                        CaptureLedgerSnapshot(2, captures.records)
                    ),
                    v
                )
            )
        assertEquals(RestoreRefusal.VERSION, future.reason)
        assertEquals("captures.version", future.path)
        assertTrue(
            restore(
                RelayStateSnapshot(2, ledger, emptyList(), emptyList(), captures),
                v
            ) is RestoreResult.Restored
        )
        assertEquals(1, RelayStateSnapshot.LEGACY_VERSION)
    }

    // -- hostile snapshots ----------------------------------------------------------------------------------------------------------------

    @Test fun everyTamperedFieldIsRefusedWithItsPathAndNeverShortened() {
        val (v, keys, _) = everyState()
        val good = v.captures.snapshot()
        val index = good.records.indexOfFirst {
            it.captureId ==
                keys.getValue("admitting").captureId.value
        }
        val ended = good.records.indexOfFirst {
            it.captureId ==
                keys.getValue("ended-failed").captureId.value
        }
        val recording = good.records.indexOfFirst {
            it.captureId ==
                keys.getValue("recording").captureId.value
        }
        val other = good.records.first { it.captureId != good.records[index].captureId }
        fun with(
            position: Int,
            change: (CaptureRecordSnapshot) -> CaptureRecordSnapshot
        ): RestoreResult.Refused {
            val records = good.records.toMutableList()
            records[position] = change(records[position])
            return refused(
                restore(
                    RelayStateSnapshot(
                        2,
                        v.h.ledger.snapshot(),
                        emptyList(),
                        emptyList(),
                        CaptureLedgerSnapshot(1, records)
                    ),
                    v
                )
            )
        }
        val cases: List<Triple<String, RestoreResult.Refused, Pair<RestoreRefusal, String>>> =
            listOf(
                Triple(
                    "scope",
                    with(index) { it.copy(scope = it.scope.copy(profileId = "nope")) },
                    RestoreRefusal.INVALID_SCOPE to "captures.records[$index].scope"
                ),
                Triple(
                    "conversation",
                    with(index) { it.copy(conversationId = "x") },
                    RestoreRefusal.INVALID_ID to "captures.records[$index].conversationId"
                ),
                Triple(
                    "capture id",
                    with(index) {
                        it.copy(captureId = "6e87dff2-3cf6-5fb9-91b4-11756446af6a")
                    },
                    RestoreRefusal.INVALID_ID to "captures.records[$index].captureId"
                ),
                Triple(
                    "stamp",
                    with(index) { it.copy(createdAtMs = it.createdAtMs + 1) },
                    RestoreRefusal.INCONSISTENT to "captures.records[$index].createdAtMs"
                ),
                Triple(
                    "state",
                    with(index) { it.copy(state = "SENT") },
                    RestoreRefusal.INVALID_FIELD to "captures.records[$index].state"
                ),
                Triple(
                    "linked id",
                    with(index) { it.copy(linkedRequest = "zzz") },
                    RestoreRefusal.INVALID_ID to "captures.records[$index].linkedRequest"
                ),
                Triple(
                    "admitting without a link",
                    with(index) { it.copy(linkedRequest = null) },
                    RestoreRefusal.INCONSISTENT to "captures.records[$index]"
                ),
                Triple(
                    "link on a recording",
                    with(recording) {
                        it.copy(linkedRequest = other.captureId)
                    },
                    RestoreRefusal.INCONSISTENT to "captures.records[$recording]"
                ),
                Triple(
                    "provider",
                    with(index) { it.copy(provider = "billed") },
                    RestoreRefusal.INVALID_FIELD to "captures.records[$index].provider"
                ),
                Triple(
                    "provider before any answer",
                    with(recording) {
                        it.copy(provider = "completed")
                    },
                    RestoreRefusal.INCONSISTENT to "captures.records[$recording].provider"
                ),
                Triple(
                    "end",
                    with(ended) { it.copy(end = "WHENEVER") },
                    RestoreRefusal.INVALID_FIELD to "captures.records[$ended].end"
                ),
                Triple(
                    "failure",
                    with(ended) { it.copy(failure = "boom") },
                    RestoreRefusal.INVALID_FIELD to "captures.records[$ended].failure"
                ),
                Triple(
                    "ended without an end",
                    with(ended) { it.copy(end = null) },
                    RestoreRefusal.INCONSISTENT to "captures.records[$ended]"
                ),
                Triple(
                    "failed without a failure",
                    with(ended) { it.copy(failure = null) },
                    RestoreRefusal.INCONSISTENT to "captures.records[$ended]"
                ),
                Triple(
                    "terminal without a time",
                    with(ended) { it.copy(terminalAtMs = null) },
                    RestoreRefusal.INCONSISTENT to "captures.records[$ended]"
                ),
                Triple(
                    "open record with a terminal time",
                    with(index) { it.copy(terminalAtMs = 5) },
                    RestoreRefusal.INCONSISTENT to "captures.records[$index]"
                ),
                Triple(
                    "terminal before creation",
                    with(ended) {
                        it.copy(
                            terminalAtMs =
                            it.createdAtMs - 1
                        )
                    },
                    RestoreRefusal.INCONSISTENT to "captures.records[$ended].terminalAtMs"
                )
            )
        for ((name, refusal, expected) in cases) {
            assertEquals(name, expected.first, refusal.reason)
            assertEquals(name, expected.second, refusal.path)
            assertFalse(name, refusal.toString().contains(good.records[index].captureId))
        }
    }

    @Test fun duplicateRecordsSharedLinksAndIdKindClashesAreRefused() {
        val (v, keys, _) = everyState()
        val good = v.captures.snapshot()
        val admitting = good.records.first {
            it.captureId ==
                keys.getValue("admitting").captureId.value
        }
        val other = good.records.first { it.state == "CANCELLED" }
        fun refuse(
            records: List<CaptureRecordSnapshot>,
            ledger: TurnLedgerSnapshot = v.h.ledger.snapshot()
        ) = refused(
            restore(
                RelayStateSnapshot(
                    2,
                    ledger,
                    emptyList(),
                    emptyList(),
                    CaptureLedgerSnapshot(1, records)
                ),
                v
            )
        )
        assertEquals(RestoreRefusal.DUPLICATE, refuse(good.records + admitting).reason)
        // two captures cannot own one turn
        val ended = other.copy(
            state = "ENDED",
            end = "TURN_GONE",
            linkedRequest = admitting.linkedRequest
        )
        assertEquals(
            RestoreRefusal.DUPLICATE,
            refuse(
                good.records.filter {
                    it !== other
                } + ended
            ).reason
        )
        // a capture id equal to a turn request id (same value, another kind) is inconsistent
        val turnId = v.h.ledger.entries.single().key.requestId.value
        val stamp = RequestIds.timestampMs(RequestId.of(turnId)!!)
        val clash = other.copy(captureId = turnId, createdAtMs = stamp)
        val clashed = refuse(good.records.filter { it !== other } + clash)
        assertEquals(RestoreRefusal.INCONSISTENT, clashed.reason)
        assertTrue(clashed.path.endsWith(".captureId"))
        // a capture id equal to another capture's linked request is the same clash
        val linkedValue = admitting.linkedRequest!!
        val clash2 = other.copy(
            captureId = linkedValue,
            createdAtMs = RequestIds.timestampMs(RequestId.of(linkedValue)!!)
        )
        assertEquals(
            RestoreRefusal.INCONSISTENT,
            refuse(
                good.records.filter {
                    it !== other
                } + clash2
            ).reason
        )
    }

    @Test fun sixteenPerEnrollmentAndSixtyFourInTotalAreTheRestoreBoundsAndNothingIsShortened() {
        val v = VoiceHarness()
        val scopes =
            listOf(v.h.enrollment to v.h.attach()) +
                (1..3).map { v.otherScope(endpoint = "endpoint-$it") }
        for ((_, token) in scopes) {
            repeat(16) {
                val key = v.begin(token)
                v.apply(v.captures.cancel(key, v.h.now()))
            }
        }
        val full = snapshotOf(v)
        assertEquals(64, full.captures.records.size)
        assertTrue(restore(full, v) is RestoreResult.Restored)
        fun mint(seed: Int) = RequestIds.mint(
            StateTestSupport.at(seed.toLong()),
            StateTestSupport.entropy(seed + 500)
        ) as dev.quantumink.hermesgadget.protocol.relay.state.MintResult.Minted
        val template = full.captures.records.first()
        val extraId = mint(7).id
        val seventeenth = template.copy(
            captureId = extraId.value,
            createdAtMs = RequestIds.timestampMs(extraId),
            terminalAtMs = RequestIds.timestampMs(extraId)
        )
        val overScope = CaptureLedgerSnapshot(1, full.captures.records + seventeenth)
        val a =
            refused(
                restore(RelayStateSnapshot(2, full.ledger, emptyList(), emptyList(), overScope), v)
            )
        assertEquals(RestoreRefusal.TOO_MANY, a.reason)
        assertEquals("captures.records", a.path)
        // 17 in ONE scope (below the total) is refused at the 17th record, by its own scope
        val few = snapshotOf(
            VoiceHarness().also { w ->
                val t = w.h.attach()
                repeat(16) {
                    val key = w.begin(t)
                    w.apply(w.captures.cancel(key, w.h.now()))
                }
            }
        )
        val over = CaptureLedgerSnapshot(
            1,
            few.captures.records +
                few.captures.records.first().copy(
                    captureId = extraId.value,
                    createdAtMs = RequestIds.timestampMs(extraId),
                    terminalAtMs = RequestIds.timestampMs(extraId)
                )
        )
        val b =
            refused(restore(RelayStateSnapshot(2, few.ledger, emptyList(), emptyList(), over), v))
        assertEquals(RestoreRefusal.TOO_MANY, b.reason)
        assertEquals("captures.records[16].scope", b.path)
    }

    // -- ownership and TooMany ---------------------------------------------------------------------------------------------------------------

    @Test fun aHostileCaptureListIsReadOnlyUpToOnePastItsBoundAndCopiedOut() {
        val template = snapshotOf(
            VoiceHarness().also {
                it.begin(it.h.attach())
            }
        ).captures.records.single()
        var reads = 0
        val endless = object : java.util.AbstractList<CaptureRecordSnapshot>() {
            override val size: Int get() = Int.MAX_VALUE
            override fun get(index: Int): CaptureRecordSnapshot {
                reads++
                return template
            }
        }
        val section = CaptureLedgerSnapshot(1, endless)
        assertEquals(VoiceLimits.CAPTURES_TOTAL + 1, section.records.size)
        assertEquals(VoiceLimits.CAPTURES_TOTAL + 1, reads)
        val v = VoiceHarness()
        assertEquals(
            RestoreRefusal.TOO_MANY,
            refused(
                restore(
                    RelayStateSnapshot(
                        2,
                        TurnLedger.empty().snapshot(),
                        emptyList(),
                        emptyList(),
                        section
                    ),
                    v
                )
            ).reason
        )
        // a list that exposes its backing array cannot alias the snapshot
        val backing = arrayOf<Any?>(template)
        val exposing = object : java.util.AbstractList<CaptureRecordSnapshot>() {
            override val size: Int get() = 1

            override fun get(index: Int): CaptureRecordSnapshot =
                backing[index] as CaptureRecordSnapshot
            override fun toArray(): Array<Any?> = backing
        }
        val owned = CaptureLedgerSnapshot(1, exposing)
        backing[0] = template.copy(state = "CHANGED")
        assertEquals(template.state, owned.records.single().state)
        assertTrue(
            runCatching {
                (owned.records as MutableList<CaptureRecordSnapshot>).clear()
            }.isFailure
        )
        assertFalse(owned.toString().contains(template.captureId))
        assertFalse(template.toString().contains(template.captureId))
    }

    @Test fun theAggregateRefusesBeforeBuildingWhenAnyComponentIsOverItsLimit() {
        val v = VoiceHarness()
        val token = v.h.attach()
        v.begin(token)
        val ok = RelayStateSnapshot.of(v.h.ledger, emptyList(), emptyList(), v.captures)
        assertTrue(ok is SnapshotBuild.Built)
        assertEquals(1, (ok as SnapshotBuild.Built).snapshot.captures.records.size)
        // a capture transition proves the same bound before it hands out an effect: the shared definition
        for ((shape, path, limit) in listOf(
            Triple(AggregateShape(17, 0), "histories", 16),
            Triple(AggregateShape(0, 17), "deliveries", 16)
        )) {
            val step = v.captures.planFinish(
                v.h.ledger,
                v.h.authority,
                token,
                v.captures.entries.single().key,
                v.stream,
                v.counts(),
                v.h.now(),
                shape
            )
            val refusal = step.result as CapturePlan.Refused
            assertEquals(PlanRefusal.SNAPSHOT_TOO_MANY, refusal.reason)
            assertEquals(path, refusal.path)
            assertEquals(limit, refusal.limit)
        }
    }

    @Test fun terminalBillingNotesSurviveARestoreAndOnlyAnExplicitPruneDropsThem() {
        val v = VoiceHarness()
        val token = v.h.attach()
        val key = v.released(token)
        v.onResult(key, v.result(key, CaptureState.UNKNOWN, provider = VoiceProvider.UNKNOWN))
        v.h.advance(VoiceLimits.TERMINAL_RETENTION_MS + 1)
        val back = restored(snapshotOf(v), v)
        assertEquals(
            "a restore never prunes",
            CaptureEnd.UNKNOWN_OUTCOME,
            back.captures.entry(key)!!.end
        )
        assertEquals(CaptureBilling.MAY_HAVE_BEEN_BILLED, back.captures.entry(key)!!.billing)
        val pruned = back.captures.prune(v.h.now())
        assertEquals(1, pruned.result)
        assertNull(pruned.ledger.entry(key))
    }

    @Test fun anAdmittingCaptureWhoseTurnIsMissingIsReportedNotRepairedOrPreparedAgain() {
        val (v, keys, _) = everyState()
        val noTurns = TurnLedger.empty().snapshot()
        val result = restore(
            RelayStateSnapshot(2, noTurns, emptyList(), emptyList(), v.captures.snapshot()),
            v
        ) as RestoreResult.Restored
        assertTrue(RestoreNotice.CAPTURE_LINK_WITHOUT_TURN in result.notices)
        val record = result.value.captures.entry(keys.getValue("admitting"))!!
        assertEquals(CaptureLifecycle.ADMITTING, record.lifecycle)
        assertTrue(record.linkedRequest != null)
        // reconciling is explicit and records that the turn is gone; it never prepares anything
        val settle = result.value.captures.settleLinked(result.value.ledger, v.h.now())
        assertEquals(1, settle.result)
        assertEquals(CaptureEnd.TURN_GONE, settle.ledger.entry(keys.getValue("admitting"))!!.end)
        assertEquals(0, result.value.ledger.size)
    }
}
