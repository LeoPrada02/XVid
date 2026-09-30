package app.xvid.core

import java.io.File

/**
 * The phone library folder, as a real folder on disk plus an index of its
 * videos, the way the media store keeps one. Deleting a file with [deleteOutside]
 * removes it without the index knowing, like a file manager on older phones;
 * [deleteFromGallery] removes both, like the gallery does.
 */
class FakePhoneLibraryFolder(private val dir: File) : PhoneLibraryFolder {
    private val index = linkedMapOf<String, PhoneLibraryVideo>()
    var failWith: String? = null
    var listings = 0

    /** Puts a file named [name] in the folder, added at [addedAt]. */
    fun put(name: String, addedAt: Long, content: String = "video $name"): PhoneLibraryVideo {
        val file = File(dir, name).apply { writeText(content) }
        val video = PhoneLibraryVideo(id = "video:$name", name = name, addedAt = addedAt, sizeBytes = file.length())
        index[video.id] = video
        return video
    }

    fun deleteFromGallery(name: String) {
        File(dir, name).delete()
        index.remove("video:$name")
    }

    fun deleteOutside(name: String) {
        File(dir, name).delete()
    }

    override fun videos(): List<PhoneLibraryVideo> {
        listings++
        failWith?.let { throw IllegalStateException(it) }
        return index.values.toList()
    }

    override fun exists(video: PhoneLibraryVideo): Boolean = File(dir, video.name).exists()
}

/** Makes a "thumbnail" holding the video's name, and remembers which it made. */
class FakeThumbnailMaker(var fails: Boolean = false) : ThumbnailMaker {
    val made = mutableListOf<String>()

    override fun make(video: PhoneLibraryVideo, target: File) {
        made += video.name
        if (fails) throw IllegalStateException("no frame")
        target.writeText("thumbnail of ${video.name}")
    }
}
