package com.deskwoot.sdk.ui.conversations

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.deskwoot.sdk.R
import com.deskwoot.sdk.model.SessionState
import com.deskwoot.sdk.ui.InsetAware
import com.deskwoot.sdk.ui.Route
import com.deskwoot.sdk.ui.Screen
import com.deskwoot.sdk.ui.Ui
import com.deskwoot.sdk.ui.thread.ThreadAdapter
import com.deskwoot.sdk.ui.widgets.MATCH
import com.deskwoot.sdk.ui.widgets.PrimaryButton
import com.deskwoot.sdk.ui.widgets.rounded
import com.deskwoot.sdk.ui.widgets.SubHeader
import com.deskwoot.sdk.ui.widgets.TopLine
import com.deskwoot.sdk.ui.widgets.WRAP
import com.deskwoot.sdk.ui.widgets.dp
import com.deskwoot.sdk.ui.widgets.linear
import com.deskwoot.sdk.ui.widgets.textStyle

/**
 * "Your conversations": every conversation of the
 * visitor, newest first, the unread ones marked; "New conversation" in a
 * footer; an empty text when there is none.
 */
internal class ConversationsScreen(ui: Ui, route: Route) : Screen(ui, route), InsetAware {
    private val ctx = ui.context
    private lateinit var header: SubHeader
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private val adapter = RowsAdapter()
    private var topInset = 0
    private val sheet = ui.design.isSheet(route)
    private val empfang = ui.design.tabBar(route)

    override fun createView(): View {
        val c = ui.colors
        val root = LinearLayout(ctx).apply {
            id = R.id.dw_conversations_screen
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (sheet) c.bg else ui.design.canvas(c))
        }
        header = SubHeader(ctx, ui, withFace = false, style = ui.design.headerStyle(route))
        header.title.text = ui.t(R.string.dw_your_conversations)
        header.back.setOnClickListener { ui.nav.back() }
        // In a sheet, close goes back to the chat.
        header.close.setOnClickListener { if (sheet) ui.nav.goTo(Route.Start) else ui.nav.close() }
        header.setPadding(header.paddingLeft, topInset + ctx.dp(4), header.paddingRight, header.paddingBottom)
        root.addView(header, linear(MATCH, WRAP))
        val middle = FrameLayout(ctx)
        list = RecyclerView(ctx).apply {
            id = R.id.dw_conversations_list
            layoutManager = LinearLayoutManager(ctx)
            adapter = this@ConversationsScreen.adapter
            clipToPadding = false
            // Body padding 0 20, top 8; rows reach 10 into it.
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
        }
        if (empfang) {
            // Empfang: the rows inside one card on the canvas, room for the floating button under them.
            list.setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4))
            list.background = ctx.rounded(c.eCard, 16f, c.line)
            list.clipToOutline = true
            middle.addView(list, FrameLayout.LayoutParams(MATCH, WRAP).apply { setMargins(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(80)) })
        } else {
            middle.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        empty = TextView(ctx).apply {
            id = R.id.dw_no_conversations
            text = ui.t(R.string.dw_no_conversations)
            textStyle(14.5f, 400, c.ink2, 1.5f)
            setPadding(ctx.dp(24), ctx.dp(22), ctx.dp(24), ctx.dp(22))
            visibility = View.GONE
        }
        middle.addView(empty, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(middle, linear(MATCH, 0, 1f))
        if (empfang) {
            // A floating pill, centered 72 above the bottom, and the tab bar.
            middle.addView(PrimaryButton(ctx, ui, ui.t(R.string.dw_new_conversation), R.drawable.dw_ic_pencil, iconAtStart = true).apply {
                id = R.id.dw_new_conversation
                pill()
                setOnClickListener { ui.nav.startConversation(null) }
            }, FrameLayout.LayoutParams(WRAP, WRAP, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ctx.dp(16) })
            if (ui.config.helpCenterEnabled) root.addView(com.deskwoot.sdk.ui.design.EmpfangTabBar(ctx, ui, helpSelected = false), linear(MATCH, WRAP))
            return root
        }
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = TopLine(c.bg, c.line, ctx.dp(1))
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(14))
        }
        footer.addView(PrimaryButton(ctx, ui, ui.t(R.string.dw_new_conversation), R.drawable.dw_ic_pencil, iconAtStart = true).apply {
            id = R.id.dw_new_conversation
            setOnClickListener { ui.nav.startConversation(null) }
        }, linear(MATCH, WRAP))
        root.addView(footer, linear(MATCH, WRAP))
        return root
    }

    override fun onTopInset(top: Int) {
        topInset = if (sheet) 0 else top
        if (isBuilt) header.setPadding(header.paddingLeft, top + ctx.dp(4), header.paddingRight, header.paddingBottom)
    }

    override fun render(state: SessionState) {
        val rows = ConversationRows(ui.people, ui.strings, ui.formats).build(state.conversations, System.currentTimeMillis())
        // The hairline is part of the item: a row that stops being the last one is bound again and gets its line.
        adapter.submitList(rows.mapIndexed { i, row -> Item(row, separator = i < rows.lastIndex) })
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    /** A row of the list and whether the hairline under it shows. */
    private data class Item(val row: ConversationRow, val separator: Boolean)

    override fun destroy() {
        if (this::list.isInitialized) list.adapter = null
    }

    private inner class RowsAdapter : ListAdapter<Item, RowsAdapter.Holder>(ThreadAdapter.diffConfig(SAME)) {
        inner class Holder(val row: ConversationRowView) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ConversationRowView(parent.context, ui).apply { layoutParams = RecyclerView.LayoutParams(MATCH, WRAP) })

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            holder.row.bind(item.row) { ui.nav.openConversation(item.row.id) }
            holder.row.separator = item.separator
        }
    }

    private companion object {
        val SAME = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean = oldItem.row.id == newItem.row.id

            override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean = oldItem == newItem
        }
    }
}
