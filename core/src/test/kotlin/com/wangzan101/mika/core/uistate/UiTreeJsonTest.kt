package com.wangzan101.mika.core.uistate

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeJsonTest {
    private fun leaf(ref: String, text: String) = UiTreeNode(
        role = "Button", bounds = listOf(0, 0, 500, 100), ref = ref, text = text,
        clickable = true, checkable = true, checked = false,
    )

    private fun screen(second: String = "查看", extra: Boolean = false): ScreenSnapshot {
        val children = listOf(leaf("@e1", "午餐"), leaf("@e2", second)) +
            if (extra) listOf(leaf("@e3", "新增")) else emptyList()
        val root = UiTreeNode("RecyclerView", listOf(0, 0, 999, 999), scrollable = true, children = children)
        val window = UiWindowTree(7, "application", 2, "订单", "com.example", true, true, listOf(root))
        val elements = children.mapIndexed { i, n ->
            UiElement(n.ref!!, "Button", n.text!!, 100, 100 + i * 100, n.bounds, null, true, false, false)
        }
        return ScreenSnapshot("port", "com.example", elements, 1080, 2400, windows = listOf(window))
    }

    @Test fun completeHierarchyAndStatesAreVisible() {
        val out = UiTreeJson.windows(screen()).single().jsonObject
        assertEquals("订单", out["title"]!!.jsonPrimitive.content)
        val root = out["nodes"]!!.jsonArray.single().jsonObject
        assertEquals("true", root["scrollable"]!!.jsonPrimitive.content)
        val children = root["children"]!!.jsonArray
        assertEquals(2, children.size)
        assertEquals("@e2", children[1].jsonObject["ref"]!!.jsonPrimitive.content)
        assertEquals("false", children[1].jsonObject["checked"]!!.jsonPrimitive.content)
    }

    @Test fun changedChildDoesNotRepeatUnchangedSibling() {
        val before = screen()
        val after = screen(second = "已查看", extra = true)
        val change = UiTreeJson.changes(before, after)
        val updated = change["updated"]!!.jsonArray
        assertEquals(listOf("w7/0/1", "w7/0/2"), updated.map { it.jsonObject["path"]!!.jsonPrimitive.content })
        assertEquals("已查看", updated[0].jsonObject["node"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("新增", updated[1].jsonObject["node"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(change["removed"]!!.jsonArray.isEmpty())
    }

    @Test fun actionResultCarriesTreeChangeWithStablePath() {
        val stabilizer = RefStabilizer("p")
        val before = stabilizer.next(screen())
        val next = screen()
        val changedWindows = next.windows.map { window ->
            window.copy(nodes = window.nodes.map { root ->
                root.copy(children = root.children.mapIndexed { i, child ->
                    if (i == 1) child.copy(checked = true) else child
                })
            })
        }
        val after = stabilizer.next(next.copy(windows = changedWindows))
        val result = InterfaceUpdate.between(before, after, baseVisible = true, maxElements = Int.MAX_VALUE)
        assertEquals("false", result["full"]!!.jsonPrimitive.content)
        val updated = result["treeChanges"]!!.jsonObject["updated"]!!.jsonArray.single().jsonObject
        assertEquals("w7/0/1", updated["path"]!!.jsonPrimitive.content)
        assertEquals("true", updated["node"]!!.jsonObject["checked"]!!.jsonPrimitive.content)
    }

    @Test fun stableRefsAlsoMapIntoTree() {
        val port = screen().copy(elements = screen().elements.mapIndexed { i, e -> e.copy(ref = "@e${i + 8}") },
            windows = screen().windows.map { w -> w.copy(nodes = w.nodes.map { root ->
                root.copy(children = root.children.mapIndexed { i, n -> n.copy(ref = "@e${i + 8}") })
            }) })
        val model = RefStabilizer("p").next(port)
        val output = UiTreeJson.windows(model.screen).single().jsonObject["nodes"]!!.jsonArray.single().jsonObject["children"]!!.jsonArray
        assertEquals(model.elements.map { it.ref }, output.map { it.jsonObject["ref"]!!.jsonPrimitive.content })
        assertFalse(output[0].jsonObject.containsKey("children"))
    }

    @Test fun compactCollapsesSingleChildWrapperChainsAndKeepsMultiChildRowGrouping() {
        // 模拟 DecorView → LinearLayout → FrameLayout → 行容器(左标题 + 右开关) + 分割线
        val row = UiTreeNode(
            role = "LinearLayout",
            bounds = listOf(0, 100, 999, 200),
            children = listOf(
                UiTreeNode("TextView", listOf(40, 120, 400, 180), ref = "@e1", text = "WLAN"),
                UiTreeNode("View", listOf(400, 120, 410, 180)), // 空占位叶子
                UiTreeNode("Switch", listOf(850, 120, 960, 180), ref = "@e2", clickable = true, checkable = true, checked = true),
            ),
        )
        val wrapped = UiTreeNode(
            role = "FrameLayout",
            bounds = listOf(0, 0, 999, 999),
            children = listOf(
                UiTreeNode("View", listOf(0, 0, 999, 36)), // 小面积空背景块
                UiTreeNode(
                    role = "LinearLayout",
                    bounds = listOf(0, 36, 999, 999),
                    children = listOf(
                        UiTreeNode("FrameLayout", listOf(0, 36, 999, 999), children = listOf(row)),
                    ),
                ),
            ),
        )
        val compacted = UiTree.compact(listOf(wrapped))
        assertEquals(1, compacted.size)
        val keptRow = compacted.single()
        assertEquals("LinearLayout", keptRow.role)
        assertEquals(listOf("@e1", "@e2"), keptRow.children.map { it.ref })
    }

    @Test fun compactPreservesLargeBlindAreaCanvasAndDropsEmptyLayouts() {
        val canvas = UiTreeNode("View", listOf(0, 600, 999, 999))
        val emptyLayout = UiTreeNode("FrameLayout", listOf(0, 0, 999, 999))
        val divider = UiTreeNode("View", listOf(0, 598, 999, 600))
        val compacted = UiTree.compact(listOf(emptyLayout, divider, canvas))
        assertEquals(listOf(canvas), compacted)
    }

    @Test fun dedupesIdenticalTextFieldsAndCleansResourceIds() {
        assertEquals("search_src_text", UiTree.readableResourceId("com.tencent.mm:id/search_src_text"))
        assertNull(UiTree.readableResourceId("com.tencent.mm:id/j5t"))
        val node = UiTreeNode(
            role = "TextView",
            bounds = listOf(0, 0, 500, 100),
            ref = "@e1",
            text = "搜索",
            description = "搜索",
            hintText = "搜索",
        )
        val compacted = UiTree.compact(listOf(node)).single()
        assertEquals("搜索", compacted.text)
        assertNull(compacted.description)
        assertNull(compacted.hintText)
        val json = UiTreeJson.windows(
            ScreenSnapshot("p", "com.example", emptyList(), 1080, 2400,
                windows = listOf(UiWindowTree(1, "application", 0, null, "com.example", true, true, listOf(node))))
        ).single().jsonObject["nodes"]!!.jsonArray.single().jsonObject
        assertEquals("搜索", json["text"]!!.jsonPrimitive.content)
        assertFalse(json.containsKey("description"))
        assertFalse(json.containsKey("hintText"))
    }

    @Test fun displayCompactionKeepsRawIdsAndFieldMetadataInSnapshot() {
        val field = UiTreeNode("EditText", listOf(10, 100, 900, 200), ref = "@e1",
            text = "搜索", description = "搜索", hintText = "搜索", resourceId = "com.example:id/j5t", editable = true)
        val wrapper = UiTreeNode("FrameLayout", listOf(0, 0, 999, 999), children = listOf(field))
        val snapshot = ScreenSnapshot("p", "com.example", emptyList(), 1080, 2400,
            windows = listOf(UiWindowTree(1, "application", 0, null, "com.example", true, true, listOf(wrapper))))

        val displayed = UiTreeJson.windows(snapshot).single().jsonObject["nodes"]!!.jsonArray.single().jsonObject
        assertEquals("EditText", displayed["role"]!!.jsonPrimitive.content)
        assertFalse(displayed.containsKey("resourceId"))
        assertFalse(displayed.containsKey("description"))
        assertFalse(displayed.containsKey("hintText"))
        val rawField = snapshot.windows.single().nodes.single().children.single()
        assertEquals("com.example:id/j5t", rawField.resourceId)
        assertEquals("搜索", rawField.description)
        assertEquals("搜索", rawField.hintText)
    }

    @Test fun displayKeepsContainersWithReadableIdsAndCollectionMetadata() {
        val child = UiTreeNode("TextView", listOf(10, 100, 500, 180), text = "账户")
        val named = UiTreeNode("FrameLayout", listOf(0, 80, 999, 220),
            resourceId = "com.example:id/account_header", children = listOf(child))
        val collection = UiTreeNode("ViewGroup", listOf(0, 80, 999, 220), children = listOf(child), listContainer = true)
        val compacted = UiTree.compact(listOf(named, collection))
        assertEquals("account_header", compacted[0].resourceId)
        assertEquals(listOf(child), compacted[0].children)
        assertEquals(collection, compacted[1])
    }

    @Test fun treeChangesUseTheSameCompactedPathsAsFullOutput() {
        fun wrapped(text: String, extraWrapper: Boolean = false): ScreenSnapshot {
            val button = leaf("@e1", text)
            val content = if (extraWrapper) UiTreeNode("FrameLayout", button.bounds, children = listOf(button)) else button
            val root = UiTreeNode("FrameLayout", listOf(0, 0, 999, 999), children = listOf(
                UiTreeNode("View", listOf(0, 0, 999, 10)), content,
            ))
            return ScreenSnapshot("p", "com.example", emptyList(), 1080, 2400,
                windows = listOf(UiWindowTree(7, "application", 0, null, "com.example", true, true, listOf(root))))
        }
        val before = wrapped("查看")
        val unchanged = UiTreeJson.changes(before, wrapped("查看", extraWrapper = true))
        assertTrue(unchanged["updated"]!!.jsonArray.isEmpty())
        assertTrue(unchanged["removed"]!!.jsonArray.isEmpty())

        val after = wrapped("已查看", extraWrapper = true)
        val updated = UiTreeJson.changes(before, after)["updated"]!!.jsonArray.single().jsonObject
        assertEquals("w7/0", updated["path"]!!.jsonPrimitive.content)
        assertEquals(UiTreeJson.windows(after).single().jsonObject["nodes"]!!.jsonArray.single(), updated["node"])
    }

    @Test fun systemBarAndDecorFiltersSkipBarsWhileKeepingInteractiveSystemWindows() {
        assertTrue(UiTree.isSystemDecorId("android:id/statusBarBackground"))
        assertTrue(UiTree.isSystemDecorId("android:id/navigationBarBackground"))
        assertFalse(UiTree.isSystemDecorId("com.example:id/content_container"))
        assertTrue(UiTree.isSystemBarWindow(isSystemType = true, packageName = "com.android.systemui", title = "StatusBar", focused = false))
        assertTrue(UiTree.isSystemBarWindow(isSystemType = true, packageName = "com.android.systemui", title = null, focused = false, bounds = listOf(0, 0, 999, 40)))
        assertFalse(UiTree.isSystemBarWindow(isSystemType = true, packageName = "com.android.systemui", title = "屏幕录制", focused = true, bounds = listOf(100, 300, 900, 700)))
        assertFalse(UiTree.isSystemBarWindow(isSystemType = false, packageName = "com.android.systemui", title = "StatusBar", focused = false))
    }
}
