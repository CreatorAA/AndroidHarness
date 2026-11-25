package com.androidharness.app.assistant

import org.junit.Assert.*
import org.junit.Test

class PhoneReviewTest {
    @Test fun aModelProposalIsBoundToOneInvocationPageAndDeadline() {
        val page = Any()
        val review = PhoneReview("这个人在惊讶什么", "app", page, 4, 10)
        assertTrue(review.isCurrent("app", page, 4, 50))
        assertFalse(review.isCurrent("app", Any(), 4, 50))
        assertFalse(review.isCurrent("other", page, 4, 50))
        assertFalse(review.isCurrent("app", page, 5, 50))
        assertFalse(review.isCurrent("app", page, 4, 120_011))
    }
}
