package com.example.runningapp.data

/**
 * A [StageGraduationJudge] a test can decide the answer of (#514).
 *
 * The three answers the app has to behave differently for are all sayable here: a set of Runs that
 * met the requirement, an empty set (a judgement — they did not), and null (the judge could not be
 * reached, which is not a judgement at all). [canBeAsked] false is the fourth, and different again:
 * a build with no key, where no Stage graduates and the coach still writes the debrief.
 *
 * [lastQuestion] is what it was asked, so a test can assert which Runs were put to it — the Walk
 * and the Open Run being absent is now the whole of the rule that used to be a prompt sentence.
 */
class FakeGraduationJudge(
    override val canBeAsked: Boolean = true,
    private val answer: (GraduationQuestion) -> Set<Long>? = { emptySet() },
) : StageGraduationJudge {

    var lastQuestion: GraduationQuestion? = null
        private set

    override suspend fun runsAnsweringRequirement(question: GraduationQuestion): Set<Long>? {
        lastQuestion = question
        return answer(question)
    }

    companion object {
        /** A judge that graduates on every Run it is asked about. */
        fun granting() = FakeGraduationJudge { question -> question.candidates.map { it.runId }.toSet() }

        /** A judge that is up and says no, which is what it says nearly every time. */
        fun refusing() = FakeGraduationJudge { emptySet() }

        /** A judge that could not be reached, which stops the whole evaluation. */
        fun unreachable() = FakeGraduationJudge { null }
    }
}
