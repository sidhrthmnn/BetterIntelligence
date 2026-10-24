package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.engine.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatContextMemoryTest {
    @Test fun longSessionKeepsItsOriginalSubjectAndNewerCorrectionsWithAttribution() {
        val pairs = listOf(ChatTurn("user", "My game is Colour Circle"), ChatTurn("assistant", "Try colour matching or a timed mode."),
            ChatTurn("user", "Use the second option"), ChatTurn("assistant", "Then use timed levels.")) +
            (1..15).flatMap { listOf(ChatTurn("user", "Unrelated task $it"), ChatTurn("assistant", "A proposed answer $it")) } +
            listOf(ChatTurn("user", "My game is now called Prism"), ChatTurn("assistant", "Use Prism")) +
            (1..6).flatMap { listOf(ChatTurn("user", "Recent detail $it"), ChatTurn("assistant", "Detail reply $it")) }
        val summary = ConversationContext.summarize(pairs)
        assertTrue(summary.contains("Colour Circle")); assertTrue(summary.contains("now called Prism"))
        assertTrue(summary.contains("Assistant proposed (unverified)"))
        val selected = ConversationContext.summaryWithinBudget(summary, 2048, "What is my game called?")
        assertTrue(selected.contains("Colour Circle")); assertTrue(selected.contains("Prism"))
        assertTrue(selected.indexOf("Colour Circle") < selected.indexOf("Prism"))
        assertFalse(ConversationContext.summarize(listOf(ChatTurn("user", "Other session"), ChatTurn("assistant", "Other answer"))).contains("Prism"))
    }
    @Test fun sessionSummaryNeverNeedsGlobalMemoryOrRemoteSummarization() {
        val turns = (1..10).flatMap { listOf(ChatTurn("user", "Remember my local marker $it"), ChatTurn("assistant", "A suggestion")) }
        val summary = ConversationContext.summarize(turns)
        assertTrue(summary.startsWith(ConversationContext.summaryHeader))
        assertFalse(summary.contains("marker 10")) // Recent messages are carried separately in full.
        assertTrue(ConversationContext.summaryWithinBudget(summary, 512, "marker 4").length <= 256)
    }
    @Test fun summariesAndHistorySurviveReopenAndRemainIsolated() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "context-restart.db"; context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val a = db.conversationDao().insert(ConversationEntity(title = "A"))
        val b = db.conversationDao().insert(ConversationEntity(title = "B"))
        db.chatDao().insertMessage(ChatMessageEntity(conversationId = a, role = "user", content = "My game is Colour Circle"))
        db.conversationDao().saveContext(a, "User previously said: My game is Colour Circle", 1)
        db.conversationDao().saveContext(b, "User previously said: My app is Finance", 2)
        db.close()
        db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            assertTrue(db.conversationDao().get(a)!!.contextSummary.contains("Colour Circle"))
            assertFalse(db.conversationDao().get(b)!!.contextSummary.contains("Colour Circle"))
            assertEquals(1, db.chatDao().messages(a).first().size)
            db.conversationDao().rename(a, "Game ideas", 100)
            assertEquals("Game ideas", db.conversationDao().get(a)!!.title)
            db.conversationDao().saveContext(a, "", 0)
            db.chatDao().deleteConversationMessages(a); db.conversationDao().delete(a)
            assertNotNull(db.conversationDao().get(b)); assertEquals("User previously said: My app is Finance", db.conversationDao().get(b)!!.contextSummary)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun relevantOlderTurnAndNewestCorrectionFitTheSameBudget() {
        val turns = listOf(ChatTurn("user", "My game is Colour Circle"), ChatTurn("assistant", "Let's design Colour Circle")) +
            (1..15).flatMap { listOf(ChatTurn("user", "Unrelated task $it"), ChatTurn("assistant", "Details $it")) } +
            listOf(ChatTurn("user", "Colour Circle is now called Prism"), ChatTurn("assistant", "Use Prism from now on"))
        val chosen = ConversationContext.history(turns, "Improve Colour Circle level design", "System", 700, 100) { it.length / 4 + 1 }
        assertTrue(chosen.any { it.content == "My game is Colour Circle" })
        assertEquals("Use Prism from now on", chosen.last().content)
        assertTrue(chosen.sumOf { it.content.length / 4 + 13 } <= 700 - 100 - 96)
    }
    @Test fun orphanedOrFailedTurnsCannotShiftRolePairing() {
        val chosen = ConversationContext.history(listOf(ChatTurn("user", "Orphan"), ChatTurn("user", "Actual"), ChatTurn("assistant", "Reply")), "Next", "System", 512, 64)
        assertEquals(listOf("Actual", "Reply"), chosen.map { it.content })
        assertTrue(ConversationContext.summaryWithinBudget("Long statement\n".repeat(100), 512).length <= 256)
    }
}
