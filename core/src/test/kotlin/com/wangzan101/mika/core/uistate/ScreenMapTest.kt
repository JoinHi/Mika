package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMapTest {
    private fun screen(nodes: List<UiTreeNode>) = ScreenSnapshot(
        "p1", "com.example", listOf(
            UiElement("@e1", "Button", "", 200, 150, listOf(100, 100, 300, 200), null, true, false, false),
        ), 1080, 2400, windows = listOf(
            UiWindowTree(7, "application", 1, null, "com.example", true, true, nodes),
        ),
    )

    @Test fun observationLinksUseDisplayedPathsWhileKeepingTheRawTree() = runTest {
        val tree = listOf(UiTreeNode("FrameLayout", listOf(0, 0, 999, 999), children = listOf(
            UiTreeNode("View", listOf(0, 0, 999, 10)),
            UiTreeNode("Button", listOf(100, 100, 300, 200), ref = "@e1", clickable = true,
                children = listOf(UiTreeNode("TextView", listOf(140, 125, 260, 170)))),
        )))
        val source = screen(tree)
        val port = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = source
            override suspend fun readVisibleText() = listOf(VisibleTextLine("搜索", listOf(150, 130, 250, 165)))
        }
        val result = toolDispatcher(port).executeRead(Part.ToolCall("o", "phone_observe", "{}"))
        val content = result.content
        val link = content["screenMap"]!!.jsonObject["ocrToControl"]!!.jsonArray.single().jsonObject
        assertEquals("w7/0", link["treePath"]!!.jsonPrimitive.content)
        assertEquals("@e1", link["ref"]!!.jsonPrimitive.content)
        assertEquals("0", link["ocrIndexes"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("搜索", content["visibleText"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        val displayed = content["tree"]!!.jsonArray.single().jsonObject["nodes"]!!.jsonArray.single().jsonObject
        assertEquals(link["ref"], displayed["ref"])
        assertFalse(displayed.containsKey("children"))
        assertEquals(tree, source.windows.single().nodes)
    }

    @Test fun overlappingUnrelatedControlsRemainUnlinked() {
        val nodes = listOf(
            UiTreeNode("Button", listOf(100, 100, 300, 200), ref = "@e1", clickable = true),
            UiTreeNode("Button", listOf(100, 100, 300, 200), ref = "@e2", clickable = true),
        )
        assertNull(ScreenMap.json(screen(nodes), listOf(VisibleTextLine("搜索", listOf(150, 130, 250, 165)))))
    }

    @Test fun distantTextIsNotAssignedToAControl() {
        val nodes = listOf(UiTreeNode("Button", listOf(100, 100, 300, 200), ref = "@e1", clickable = true))
        assertNull(ScreenMap.json(screen(nodes), listOf(VisibleTextLine("别的区域", listOf(700, 700, 900, 750)))))
    }

    @Test fun upperWindowPreventsLinkToCoveredAppControl() {
        val base = screen(listOf(UiTreeNode("Button", listOf(100, 100, 300, 200), ref = "@e1", clickable = true)))
        val overlay = UiWindowTree(8, "system", 2, null, "android", true, true,
            listOf(UiTreeNode("TextView", listOf(100, 100, 300, 200))))
        val source = base.copy(windows = base.windows + overlay)
        // 上层无字小窗口在展示中可省略,但仍须阻止 OCR 关联到被它遮住的按钮。
        assertTrue(UiTreeJson.windows(source).last().jsonObject["nodes"]!!.jsonArray.isEmpty())
        assertNull(ScreenMap.json(source,
            listOf(VisibleTextLine("弹窗文字", listOf(150, 130, 250, 165)))))
    }
}
