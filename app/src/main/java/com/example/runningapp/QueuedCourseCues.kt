package com.example.runningapp

import android.util.Log
import com.example.runningapp.routes.CourseCueQueue
import com.example.runningapp.routes.CourseSaying
import com.example.runningapp.run.CueTag

/**
 * The course's voice, wired to the Run's cue queue (#58, #377, #456, #479).
 *
 * Everything the course says goes in under one name, [CueTag.COURSE], and at one priority,
 * [CuePriority.NAVIGATION] — the top of the queue, because a runner going the wrong way is going
 * further the wrong way for as long as a split announcement takes to finish. It still never cuts one
 * off mid-sentence; nothing in this app does (#53). And it goes through the Run's own [cues], so the
 * end of the Run takes a course cue back like any other (#220).
 *
 * Those facts are the whole of this class. They used to be three callbacks wired by hand in the
 * service, with nothing to test them.
 */
class QueuedCourseCues(private val cues: RunCueQueue) : CourseCueQueue {

    override fun enqueue(saying: CourseSaying): Long? {
        Log.d(HrForegroundService.TAG, "Course cue: ${saying.spoken}")
        return cues.enqueue(saying.spoken, CuePriority.NAVIGATION, CueTag.COURSE)
    }

    override fun takeBackEveryCourseCue() = cues.takeBack(CueTag.COURSE)

    override fun takeBack(tickets: List<Long>) = cues.takeBack(tickets)
}
