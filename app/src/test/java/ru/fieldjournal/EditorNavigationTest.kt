package ru.fieldjournal

import android.os.Looper
import android.os.SystemClock
import android.text.style.URLSpan
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-mdpi")
class EditorNavigationTest {
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun existingDescriptionLinksOpenOnTapAndRemainEditable() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val address = "https://ru.wikipedia.org/wiki/Статья_(археология)"
            val description = "По данным (\n$address)."
            val site = Site(title = "Стоянка", vertices = listOf(Coordinate(59.0, 33.0)),
                description = description, sources = "Архив https://example.org/fund/42.")
            activity.store.save(site)
            activity.openSite(site.id)
            val root = activity.window.decorView
            val input = root.input("Описание местности и объекта")
            layout(root)
            assertEquals(address, input.text.getSpans(0, input.length(), URLSpan::class.java).single().url)
            tapText(input, description.indexOf("https") + 4)
            val opened = requireNotNull(shadowOf(activity).nextStartedActivity) { "Tapping the URL must open the browser" }
            assertEquals(android.content.Intent.ACTION_VIEW, opened.action)
            assertEquals(address, opened.dataString)
            tapText(input, 2)
            assertTrue(input.hasFocus())
            assertNull(shadowOf(activity).nextStartedActivity)
            input.setText("Новая ссылка https://example.org/field?item=2#photo.")
            assertEquals("https://example.org/field?item=2#photo",
                input.text.getSpans(0, input.length(), URLSpan::class.java).single().url)
            val sources = root.input("Литература, архивные шифры и ссылки")
            assertEquals("https://example.org/fund/42",
                sources.text.getSpans(0, sources.length(), URLSpan::class.java).single().url)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun linksInSiteAndCompactNoteFollowDraftAndSaveConfirmation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val site = Site(title = "Раскоп", vertices = listOf(Coordinate(59.0, 33.0)))
            activity.store.save(site)
            activity.openSite(site.id)
            val root = activity.window.decorView
            val siteLink = WebLink("https://example.org/archive?id=8", "Карточка архива")
            root.text("Добавить ссылку").performClick()
            var sheet = ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
            sheet.input("Адрес сайта").setText("file:///private")
            sheet.text("Добавить").performClick()
            assertNotNull(sheet.input("Адрес сайта").error)
            sheet.input("Адрес сайта").setText(siteLink.url)
            sheet.input("Название ресурса (необязательно)").setText(siteLink.title)
            sheet.text("Добавить").performClick()
            assertEquals(emptyList<WebLink>(), activity.store.site(site.id)?.links)
            controller.recreate()
            val restored = controller.get()
            val restoredRoot = restored.window.decorView
            restoredRoot.action("Открыть ссылку ${siteLink.title}").performClick()
            assertEquals(siteLink.url, shadowOf(restored).nextStartedActivity.dataString)
            restoredRoot.action("Сохранить и закрыть").performClick()
            chooseConfirmation("Сохранить")
            assertEquals(listOf(siteLink), restored.store.site(site.id)?.links)

            restored.createNote(site.id, compact = true)
            restoredRoot.assertNoteSections("Короткая заметка")
            assertFalse(restoredRoot.input("Автор / группа").isShown)
            restoredRoot.text("Дополнительные поля").performClick()
            assertTrue(restoredRoot.input("Автор / группа").isShown)
            restoredRoot.input("Автор / группа").setText("Полевая группа")
            val noteBody = "Схема https://example.org/map"
            restoredRoot.input("Короткая заметка").setText(noteBody)
            layout(restoredRoot)
            tapText(restoredRoot.input("Короткая заметка"), noteBody.indexOf("https") + 4)
            assertEquals("https://example.org/map", shadowOf(restored).nextStartedActivity.dataString)
            restoredRoot.text("Добавить ссылку").performClick()
            sheet = ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
            val noteLink = WebLink("https://www.example.org/article")
            sheet.input("Адрес сайта").setText("www.example.org/article")
            sheet.text("Добавить").performClick()
            restoredRoot.action("Убрать ссылку ${noteLink.url}").performClick()
            assertFalse(restoredRoot.descendants().any { it.contentDescription == "Открыть ссылку ${noteLink.url}" })
            restoredRoot.text("Добавить ссылку").performClick()
            sheet = ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
            sheet.input("Адрес сайта").setText(noteLink.url)
            sheet.text("Добавить").performClick()
            restoredRoot.action("Сохранить и закрыть").performClick()
            chooseConfirmation("Сохранить")
            val saved = restored.store.notes().single { it.siteId == site.id }
            assertEquals(noteBody, saved.body)
            assertEquals(listOf(noteLink), saved.links)
            assertEquals("Полевая группа", saved.author)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun linkedNoteReturnsToOriginAfterRecreationAndNewNoteDoesTheSame() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val site = Site(title = "Стоянка у реки", vertices = listOf(Coordinate(59.0, 33.0)))
            val note = Note(title = "Первый обход", siteId = site.id, author = "Группа разведки", weather = "Ясно")
            activity.store.save(site)
            activity.store.save(note)
            activity.openSite(site.id)
            val initial = activity.window.decorView
            initial.descendants().filterIsInstance<TextView>().first { it.text.startsWith(note.title) }.performClick()
            controller.recreate()

            val root = controller.get().window.decorView
            root.text("Запись дневника")
            root.assertNoteSections("Маршрут, работы и наблюдения")
            assertEquals(note.author, root.input("Автор / группа").text.toString())
            assertEquals(note.weather, root.input("Погода и условия работ").text.toString())
            root.action("Назад").performClick()
            root.text("Карточка объекта")
            assertEquals(site.title, root.input("Название").text.toString())

            root.text("Запись об объекте").performClick()
            root.input("Заголовок").setText("Повторный обход")
            root.action("Сохранить и закрыть").performClick()
            assertFalse(controller.get().store.notes().any { it.title == "Повторный обход" })
            chooseConfirmation("Сохранить")
            root.text("Карточка объекта")
            root.text("Сохранено · Стоянка у реки")
            assertTrue(controller.get().store.notes().any { it.siteId == site.id && it.title == "Повторный обход" })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun leavingGeometryEditKeepsTheConfirmationForOtherCardChanges() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val original = Site(title = "Стоянка", vertices = listOf(Coordinate(59.0, 33.0)))
            activity.store.save(original)
            activity.openSite(original.id)
            val root = activity.window.decorView
            root.input("Название").setText("Стоянка у реки")
            activity.editGeometry(original)
            val map = root.descendants().filterIsInstance<FieldMap>().single()
            map.onTap?.invoke(Coordinate(60.0, 34.0), null)
            root.text("Дневник").performClick()
            assertFalse(map.editingGeometry)
            val confirmation = ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
            confirmation.text("Сохранить изменения?")
            chooseConfirmation("Сохранить")
            assertTrue(root.text("Новая запись").isShown)
            val saved = requireNotNull(activity.store.site(original.id))
            assertEquals("Стоянка у реки", saved.title)
            assertEquals(original.vertices, saved.vertices)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun attachmentCopyReturnsToTheSameDraftAfterRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val started = java.util.concurrent.CountDownLatch(1)
        val resumeCopy = java.util.concurrent.CountDownLatch(1)
        try {
            val activity = controller.get()
            val site = Site(title = "Стоянка", vertices = listOf(Coordinate(59.0, 33.0)))
            activity.store.save(site)
            activity.openSite(site.id)
            val file = java.io.File(activity.cacheDir, "скан.jpg").apply { writeText("image bytes") }
            val provider = object : android.content.ContentProvider() {
                override fun onCreate() = true
                override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = null
                override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun update(
                    uri: android.net.Uri, values: android.content.ContentValues?,
                    selection: String?, selectionArgs: Array<out String>?
                ) = 0

                override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor {
                    started.countDown()
                    check(resumeCopy.await(8, java.util.concurrent.TimeUnit.SECONDS))
                    return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                }

                override fun query(
                    uri: android.net.Uri, projection: Array<out String>?, selection: String?,
                    selectionArgs: Array<out String>?, sortOrder: String?
                ) = android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)).apply {
                    addRow(arrayOf(file.name))
                }

                override fun getType(uri: android.net.Uri) = "image/jpeg"
            }
            val testAuthority = "journal.test.attachment"
            provider.attachInfo(activity.applicationContext, android.content.pm.ProviderInfo().apply {
                authority = testAuthority
                exported = true
            })
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(testAuthority, provider)
            activity.pickAttachment(site.id, note = false, photoOnly = true)
            val shadow = shadowOf(activity)
            val request = shadow.nextStartedActivityForResult
            shadow.receiveResult(request.intent, android.app.Activity.RESULT_OK, android.content.Intent().setData(
                android.net.Uri.parse("content://$testAuthority/photo")
            ))
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            controller.recreate()
            val restored = controller.get()
            resumeCopy.countDown()
            val root = restored.window.decorView
            val main = shadowOf(Looper.getMainLooper())
            val timeout = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6)
            while (root.descendants().filterIsInstance<TextView>().none { it.text == file.name } &&
                System.nanoTime() < timeout) {
                main.idle()
                Thread.sleep(10)
            }
            assertEquals(1, root.descendants().filterIsInstance<TextView>().count { it.text == file.name })
            assertTrue(restored.store.site(site.id)!!.attachments.isEmpty())
            restored.closeEditor()
            chooseConfirmation("Сохранить")
            val saved = restored.store.site(site.id)!!.attachments.single()
            assertEquals("image bytes", restored.store.attachmentFile(saved.id).readText())
        } finally {
            resumeCopy.countDown()
            controller.pause().stop().destroy()
        }
    }

    @Test fun noticesFloatAboveMapButtonsAndNewNotesRequireConfirmation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.window.decorView
            root.action(FieldIcon.POINT.description).performClick()
            layout(root)
            layout(root)
            val hint = root.text("Коснитесь карты или выберите GPS, затем нажмите галочку")
            val install = root.action(FieldIcon.SET_POINT.description)
            assertTrue(top(hint) >= top(install) + install.height)
            assertTrue(top(hint) + hint.height <= top(root.action("Моё место")))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertFalse(hint.isShown)
            root.action(FieldIcon.CANCEL.description).performClick()

            root.text("Дневник").performClick()
            root.text("Новая запись").performClick()
            layout(root)
            val save = root.action("Сохранить и закрыть")
            val back = root.action("Назад")
            assertEquals(back.width, save.width)
            assertEquals((back as android.widget.ImageButton).drawable.intrinsicWidth,
                (save as android.widget.ImageButton).drawable.intrinsicWidth)
            assertNull(root.descendants().filterIsInstance<TextView>().firstOrNull {
                it.text == "Разделы" || it.text == "Сохранено" || it.text == "Готово"
            })
            val knownIds = activity.store.notes().map(Note::id).toSet()
            root.input("Заголовок").setText("Черновая запись")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            controller.recreate()
            val restored = controller.get().window.decorView
            assertEquals("Черновая запись", restored.input("Заголовок").text.toString())
            restored.action("Сохранить и закрыть").performClick()
            assertTrue(activity.store.notes().all { it.id in knownIds })
            chooseConfirmation("Отмена")
            assertEquals("Черновая запись", restored.input("Заголовок").text.toString())
            restored.action("Назад").performClick()
            chooseConfirmation("Не сохранять")
            restored.text("Дневник")
            assertTrue(activity.store.notes().all { it.id in knownIds })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun discardingChangesKeepsStoredObjectAndClearsStagedFile() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val original = Site(title = "Исходный объект", vertices = listOf(Coordinate(59.0, 33.0)))
            activity.store.save(original)
            activity.openSite(original.id)
            val root = activity.window.decorView
            root.input("Название").setText("Изменено")
            val attachment = activity.store.addAttachment("данные".byteInputStream(), "plan.txt", "text/plain")
            root.descendants().filterIsInstance<JournalEditor>().single().stageAttachment(attachment)
            assertEquals(original, activity.store.site(original.id))
            root.action("Назад").performClick()
            chooseConfirmation("Не сохранять")
            assertEquals(original, activity.store.site(original.id))
            assertFalse(activity.store.attachmentFile(attachment.id).exists())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun quickObservationRestoresTextPhotoAndTripAfterReopeningTheApp() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = first.get()
        val site = Site(title = "Урочище", vertices = listOf(Coordinate(59.0, 33.0)))
        val trip = activity.store.trips.start("Первый выезд")
        val photo = activity.store.addAttachment("photo contents".byteInputStream(), "Урочище.jpg", "image/jpeg")
        try {
            activity.store.save(site)
            val map = activity.window.decorView.descendants().filterIsInstance<FieldMap>().single()
            map.onTap!!.invoke(site.anchor, site)
            val preview = ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
            preview.text(site.title)
            preview.text("Новое наблюдение").performClick()
            val root = activity.window.decorView
            root.input("Короткая заметка").setText("Северная граница проверена")
            root.descendants().filterIsInstance<JournalEditor>().single().stageAttachment(photo)
            root.action("Назад").performClick()
            chooseConfirmation("В черновики")
            assertTrue(activity.store.noteSummaries(site.id).isEmpty())
            assertEquals(1, activity.store.draftCount())
        } finally {
            first.pause().stop().destroy()
        }
        val second = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val reopened = second.get()
            val root = reopened.window.decorView
            root.text("Дневник").performClick()
            root.text("Черновики · 1").performClick()
            (root.text("Полевое наблюдение").parent.parent as View).performClick()
            ShadowAlertDialog.getLatestAlertDialog().window!!.decorView.text("Продолжить").performClick()
            assertEquals("Северная граница проверена", root.input("Короткая заметка").text.toString())
            root.text(photo.name)
            root.action("Сохранить и закрыть").performClick()
            chooseConfirmation("Сохранить")
            assertEquals(0, reopened.store.draftCount())
            val contents = reopened.store.trips.contents(trip.id)
            val note = contents.notes.single()
            assertEquals(site, contents.sites.single())
            assertEquals(site.id, note.siteId)
            assertEquals(trip.id, note.tripId)
            assertEquals(listOf(photo), note.attachments)
            assertEquals("photo contents", reopened.store.attachmentFile(photo.id).readText())
        } finally {
            activity.store.trips.finish(trip.id)
            second.pause().stop().destroy()
        }
    }

    @Test fun diaryShowsTripAsATabAndRestoresHiddenRouteFromMap() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val store = activity.store
        store.trips.active()?.let { store.trips.finish(it.id) }
        val trip = store.trips.start("Монастырь")
        try {
            val point = Coordinate(59.0, 33.0)
            store.save(Note(title = "Обход стен", tripId = trip.id))
            assertTrue(store.trips.append(trip.id, TrackPoint(point, trip.startedAt, 3.0, null, 0)))
            val root = activity.window.decorView
            root.text("Дневник").performClick()
            root.text("Записи")
            root.text("Выезды").performClick()
            assertTrue(root.descendants().filterIsInstance<TextView>().any { it.text.startsWith("Черновики") })
            (root.text(trip.title).parent.parent as View).performClick()
            root.text("Записи выезда · 1")
            assertTrue(root.descendants().filterIsInstance<TextView>().none { it.text == "Комплект для QGIS" })
            root.text("Показать маршрут").performClick()
            root.text("Дневник").performClick()
            root.text("Скрыть маршрут").performClick()
            root.text("Показать маршрут")
            root.text("Карта").performClick()
            root.action("Слои").performClick()
            val layers = ShadowAlertDialog.getLatestAlertDialog()
            val route = layers.window!!.decorView.text("Маршрут выезда") as android.widget.Switch
            assertFalse(route.isChecked)
            route.performClick()
            assertTrue(activity.showingTripTrack(trip.id))
            layers.dismiss()
            assertFalse(root.text("Записывается трек выезда").isShown)
        } finally {
            activity.deleteTrip(trip.id)
            controller.pause().stop().destroy()
        }
    }

    @Test fun sectionNavigationReplacesHeadersAndObjectEditorUsesFieldIconsAndOneSourceAction() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val tiles = MapTiles.get(activity)
        val previousOffline = tiles.offline
        try {
            val root = activity.window.decorView
            fun count(label: String) = root.descendants().filterIsInstance<TextView>().count { it.text == label }
            root.text("Объекты").performClick()
            assertEquals(1, count("Объекты"))
            assertEquals(0, count("На карте"))
            root.text("Дневник").performClick()
            assertEquals(1, count("Дневник"))
            layout(root)
            assertTrue(root.text("Новая запись").width > 260)
            assertTrue(top(root.text("Новая запись")) > top(root.text("Записи")))
            root.text("Данные").performClick()
            assertEquals(0, count("Данные и карты"))

            val area = Site(title = "Комплекс", vertices = listOf(
                Coordinate(59.0, 33.0), Coordinate(59.0, 33.01), Coordinate(59.01, 33.01)
            ))
            activity.store.save(area)
            activity.openSite(area.id)
            assertEquals(0, count("Название с карты"))
            assertTrue(root.action("Добавить точку внутри") is android.widget.ImageButton)
            assertTrue(root.action("Добавить область внутри") is android.widget.ImageButton)
            tiles.offline = true
            root.text("Заполнить по открытым источникам").performClick()
            val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog().window).decorView
            assertEquals(area.title, dialog.input("Название объекта и населённый пункт").text.toString())
            dialog.text("Найти в Википедии")
        } finally {
            tiles.offline = previousOffline
            controller.pause().stop().destroy()
        }
    }

    @Test fun linkedObjectsAndAttachmentsShareOneIconLayoutAndSheetsCloseWithAnIcon() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.window.decorView
            val region = Site(title = "Территория монастыря", vertices = listOf(
                Coordinate(59.0, 33.0), Coordinate(59.0, 33.06),
                Coordinate(59.06, 33.06), Coordinate(59.06, 33.0)
            ))
            val group = Site(title = "Монастырский двор", vertices = listOf(
                Coordinate(59.01, 33.01), Coordinate(59.01, 33.02), Coordinate(59.02, 33.02)
            ))
            activity.store.save(region)
            activity.store.save(group)
            activity.openSite(group.id)
            layout(root)
            fun buttons(vararg labels: String) = labels.map { root.action(it) as android.widget.ImageButton }
            val links = buttons("Выбрать родительскую область", "Добавить точку внутри", "Добавить область внутри")
            val attachments = buttons("Снять фото", "Добавить фото", "Добавить документ")
            for (row in listOf(links, attachments)) {
                assertTrue(row.all { it.parent === row.first().parent && it.height >= 48 && it.tooltipText == it.contentDescription })
                assertEquals(row.map { it.left }.sorted(), row.map { it.left })
            }
            assertEquals(links.map { it.width }, attachments.map { it.width })
            assertTrue(root.descendants().filterIsInstance<TextView>().none { it.text in listOf("Снять", "Фото", "Документ", "Родительская область") })

            links.first().performClick()
            val sheet = ShadowAlertDialog.getLatestAlertDialog()
            val contents = requireNotNull(sheet.window).decorView
            contents.text(region.title)
            assertTrue(contents.descendants().filterIsInstance<TextView>().none { it.text == "Закрыть" })
            (contents.action("Закрыть") as android.widget.ImageButton).performClick()
            assertFalse(sheet.isShowing)

            attachments[0].performClick()
            val camera = shadowOf(activity).nextStartedActivityForResult.intent
            assertEquals(android.provider.MediaStore.ACTION_IMAGE_CAPTURE, camera.action)
            assertTrue(camera.hasExtra(android.provider.MediaStore.EXTRA_OUTPUT))
            attachments[1].performClick()
            val photo = shadowOf(activity).nextStartedActivityForResult.intent
            assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, photo.action)
            assertEquals("image/*", photo.type)
            attachments[2].performClick()
            val document = shadowOf(activity).nextStartedActivityForResult.intent
            assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, document.action)
            assertEquals("*/*", document.type)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun scientificFormBackPreservesInputAndApplyingWritesOnlyTheDraft() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val site = Site(title = "Участок", vertices = listOf(Coordinate(59.0, 33.0)))
            activity.store.save(site)
            activity.openSite(site.id)
            val root = activity.window.decorView
            root.text("Научная карточка").performClick()
            val panel = ShadowAlertDialog.getLatestAlertDialog()
            panel.window!!.decorView.text("Добавить научную запись").performClick()
            val choices = ShadowAlertDialog.getLatestAlertDialog()
            val kinds = choices.listView
            val index = (0 until kinds.count).first { kinds.adapter.getItem(it).toString() == "Осмотр участка" }
            kinds.performItemClick(kinds.adapter.getView(index, null, kinds), index, kinds.adapter.getItemId(index))
            val form = ShadowAlertDialog.getLatestAlertDialog()
            form.window!!.decorView.input("Результат").setText("Зафиксирована кладка")
            form.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            chooseConfirmation("Отмена")
            assertTrue(form.isShowing)
            assertEquals("Зафиксирована кладка", form.window!!.decorView.input("Результат").text.toString())
            form.window!!.decorView.text("Применить").performClick()
            panel.window!!.decorView.action("Закрыть").performClick()
            assertFalse(panel.isShowing)
            assertTrue(activity.store.site(site.id)!!.research.entries.isEmpty())
            assertEquals("Зафиксирована кладка", activity.store.draft(site.id)!!.site!!.research.entries.single().fields["result"])
            root.action("Сохранить и закрыть").performClick()
            chooseConfirmation("Сохранить")
            assertEquals("Зафиксирована кладка", activity.store.site(site.id)!!.research.entries.single().fields["result"])
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun newTripIsAFullScreenCardAndRestoresSelectedType() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val root = controller.get().window.decorView
            root.text("Дневник").performClick()
            root.text("Выезды").performClick()
            root.text("Новый выезд").performClick()
            layout(root)
            assertTrue(top(root.text("Новый выезд")) < top(root.input("Название выезда")))
            assertFalse(root.descendants().filterIsInstance<TextView>().any { it.text == "Дневник" && it.isShown })
            root.input("Название выезда").setText("Осмотр у реки")
            (root.text("Разведка").parent as View).performClick()
            val profiles = ShadowAlertDialog.getLatestAlertDialog().listView
            val index = (0 until profiles.count).first { profiles.adapter.getItem(it) == "Мониторинг" }
            profiles.performItemClick(profiles.adapter.getView(index, null, profiles), index, profiles.adapter.getItemId(index))

            controller.recreate()
            val activity = controller.get()
            val restored = activity.window.decorView
            assertEquals("Осмотр у реки", restored.input("Название выезда").text.toString())
            restored.text("Мониторинг")
            restored.action("Сохранить и закрыть").performClick()
            val trip = requireNotNull(activity.store.trips.active())
            assertEquals("Осмотр у реки", trip.title)
            assertEquals("Мониторинг", trip.profile)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun chooseConfirmation(button: String) {
        ShadowAlertDialog.getLatestAlertDialog().window!!.decorView.text(button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 320, 640)
    }

    private fun tapText(input: EditText, offset: Int) {
        val lines = requireNotNull(input.layout)
        val line = lines.getLineForOffset(offset)
        val x = lines.getPrimaryHorizontal(offset) + input.totalPaddingLeft - input.scrollX
        val y = (lines.getLineTop(line) + lines.getLineBottom(line)) / 2f + input.totalPaddingTop - input.scrollY
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            input.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun top(view: View) = IntArray(2).also(view::getLocationOnScreen)[1]

    private fun View.assertNoteSections(bodyHint: String) {
        val sections = listOf(
            text("Описание"), input("Заголовок"), input(bodyHint), text("Фотографии и материалы"),
            text("Ссылки"), text("Учёт и источники"),
            input("Автор / группа"), input("Погода и условия работ")
        )
        val views = descendants().toList()
        val positions = sections.map(views::indexOf)
        assertTrue(positions.zipWithNext().all { (earlier, later) -> earlier < later })
    }

    private fun View.action(description: String) = descendants().first { it.contentDescription == description }
    private fun View.text(value: String) = descendants().filterIsInstance<TextView>().first { it.text == value }
    private fun View.input(hint: String) = descendants().filterIsInstance<EditText>().first { it.hint == hint }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
    }
}
