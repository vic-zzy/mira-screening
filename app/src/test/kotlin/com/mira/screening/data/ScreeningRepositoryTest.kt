package com.mira.screening.data

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mira.screening.inference.ViaClassification
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ScreeningRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: ScreeningRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Wipe between tests so they're independent
        File(context.filesDir, "records.json").delete()
        File(context.filesDir, "images").deleteRecursively()
        repo = ScreeningRepository(context)
    }

    private fun makeRecord(id: String, classification: ViaClassification = ViaClassification.NEGATIVE) =
        ScreeningRecord(
            id = id,
            timestampMs = 1_700_000_000_000L,
            classification = classification,
            confidence = 0.85f,
            patientId = null,
            imagePath = null,
            notes = null
        )

    @Test
    fun list_isEmpty_initially() = runBlocking {
        assertThat(repo.list()).isEmpty()
    }

    @Test
    fun saveAndList_returnsRecord() = runBlocking {
        repo.save(makeRecord("a"), bitmap = null, persistImage = false)
        val list = repo.list()
        assertThat(list).hasSize(1)
        assertThat(list[0].id).isEqualTo("a")
        assertThat(list[0].classification).isEqualTo(ViaClassification.NEGATIVE)
    }

    @Test
    fun save_persistsImage_whenRequested() = runBlocking {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val saved = repo.save(makeRecord("b"), bmp, persistImage = true)
        assertThat(saved.imagePath).isNotNull()
        assertThat(File(saved.imagePath!!).exists()).isTrue()
    }

    @Test
    fun save_skipsImage_whenNotRequested() = runBlocking {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val saved = repo.save(makeRecord("c"), bmp, persistImage = false)
        assertThat(saved.imagePath).isNull()
    }

    @Test
    fun save_overwritesExistingId() = runBlocking {
        repo.save(makeRecord("a", ViaClassification.NEGATIVE), null, false)
        repo.save(makeRecord("a", ViaClassification.POSITIVE), null, false)
        val list = repo.list()
        assertThat(list).hasSize(1)
        assertThat(list[0].classification).isEqualTo(ViaClassification.POSITIVE)
    }

    @Test
    fun delete_removesRecord() = runBlocking {
        repo.save(makeRecord("a"), null, false)
        repo.save(makeRecord("b"), null, false)
        repo.delete("a")
        assertThat(repo.list().map { it.id }).containsExactly("b")
    }

    @Test
    fun delete_alsoRemovesImageFile() = runBlocking {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val saved = repo.save(makeRecord("a"), bmp, persistImage = true)
        val imagePath = saved.imagePath!!
        assertThat(File(imagePath).exists()).isTrue()
        repo.delete("a")
        assertThat(File(imagePath).exists()).isFalse()
    }

    @Test
    fun roundtrip_acrossInstances() = runBlocking {
        repo.save(makeRecord("a"), null, false)
        val again = ScreeningRepository(context)
        assertThat(again.list().map { it.id }).containsExactly("a")
    }

    @Test
    fun userOverride_roundtripsCorrectly() = runBlocking {
        val r = makeRecord("a").copy(userOverride = ViaClassification.POSITIVE)
        repo.save(r, null, false)
        val loaded = repo.list().first()
        assertThat(loaded.userOverride).isEqualTo(ViaClassification.POSITIVE)
        assertThat(loaded.classification).isEqualTo(ViaClassification.NEGATIVE)
        assertThat(loaded.effectiveClassification).isEqualTo(ViaClassification.POSITIVE)
    }

    @Test
    fun effectiveClassification_fallsBackToOriginal_whenNoOverride() {
        val r = makeRecord("a", classification = ViaClassification.POSITIVE)
        assertThat(r.userOverride).isNull()
        assertThat(r.effectiveClassification).isEqualTo(ViaClassification.POSITIVE)
    }
}
