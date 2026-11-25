package com.androidharness.app.androiduse

import org.junit.Assert.*
import org.junit.Test

class PhoneImagePreviewsTest {
    private val image = PhoneNode("image", "", "", false, true, 90, 230, 530, 478, "android.widget.ImageView")
    private fun frame(vararg nodes: PhoneNode) = PhoneFrame("unknown.app", "w", 1080, 2400, 0, nodes.toList())

    @Test fun sharedObservationDescribesAnOpenableImageNotAnAvatarOrBackground() {
        val owner = image.copy(id = "owner", viewClass = "android.widget.FrameLayout", tapOwner = "owner",
            left = 84, right = 542, top = 228, bottom = 490)
        val child = image.copy(clickable = false, tapOwner = owner.id)
        val avatar = image.copy(id = "a", right = 190, bottom = 330)
        val wallpaper = image.copy(id = "b", left = 0, top = 0, right = 1080, bottom = 2400)
        val decoration = image.copy(id = "d", clickable = false)
        assertEquals(listOf(PhoneImagePreviews.Preview("image", "owner")),
            PhoneImagePreviews.from(frame(owner, child, avatar, wallpaper, decoration)))
        assertTrue(PhoneImagePreviews.from(frame(owner.copy(right = 1080,bottom = 2000),child)).isEmpty())
    }

    @Test fun flatDriverCanProveItsOwnButtonButNotAnUnrelatedParentOrPoint() {
        assertEquals(listOf(PhoneImagePreviews.Preview("image","image")), PhoneImagePreviews.from(frame(image)))
        val possibleParent = image.copy(id = "p",viewClass = "View")
        assertTrue(PhoneImagePreviews.from(frame(image.copy(clickable = false),possibleParent)).isEmpty())
        assertTrue(PhoneImagePreviews.from(frame(image.copy(right = image.left,bottom = image.top))).isEmpty())
    }

}
