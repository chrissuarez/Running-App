package com.example.runningapp.data

/**
 * A [StageGraduationJudge] a test can decide the answer of (#514, #516).
 *
 * The three answers the app has to behave differently for are all sayable here: true (the
 * requirement is met), false (a judgement — it is not) and null (the judge could not be reached,
 * which is not a judgement at all). [canBeAsked] false is the fourth, and different again: a build
 * with no key, where no Stage graduates and the coach still writes the debrief.
 *
 * [lastQuestion] is what it was asked, so a test can assert which Runs were put to it — the Walk
 * and the Open Run being absent is now the whole of the rule that used to be a prompt sentence.
 */
class FakeGraduationJudge(
    override val canBeAsked: Boolean = true,
    private val answer: (GraduationQuestion) -> Boolean? = { false },
) : StageGraduationJudge {

    var lastQuestion: GraduationQuestion? = null
        private set

    override suspend fun requirementIsMet(question: GraduationQuestion): Boolean? {
        lastQuestion = question
        return answer(question)
    }

    companion object {
        /** A judge that says the requirement is met on whatever evidence it is shown. */
        fun granting() = FakeGraduationJudge { true }

        /** A judge that is up and says no, which is what it says nearly every time. */
        fun refusing() = FakeGraduationJudge { false }

        /** A judge that could not be reached, which stops the whole evaluation. */
        fun unreachable() = FakeGraduationJudge { null }
    }
}
