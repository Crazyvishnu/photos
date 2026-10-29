package com.photosapp.ui.home

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.photosapp.R
import kotlinx.coroutines.launch

internal fun View.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun android.content.Context.color(id: Int) = androidx.core.content.ContextCompat.getColor(this, id)

class AlbumsFragment : Fragment() {
    private val vm: HomeViewModel by activityViewModels { HomeViewModel.Factory }
    private var observeView: ((androidx.lifecycle.LifecycleOwner) -> Unit)? = null
    override fun onViewCreated(view: View, state: Bundle?) {
        observeView?.invoke(viewLifecycleOwner); observeView = null
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val shortcuts = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun shortcut(label: String, icon: Int, action: () -> Unit) = MaterialButton(context,
            null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; isAllCaps = false; cornerRadius = dp(20)
            setIconResource(icon); iconTint = android.content.res.ColorStateList.valueOf(context.color(R.color.text_primary))
            setTextColor(context.color(R.color.text_primary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(context.color(R.color.surface))
            strokeWidth = 0; setOnClickListener { action() }
        }
        shortcuts.addView(shortcut("Bin", R.drawable.ic_view_bin) { TrashFragment.open(this@AlbumsFragment) },
            LinearLayout.LayoutParams(0, root.dp(64), 1f).apply { marginEnd = root.dp(8) })
        shortcuts.addView(shortcut("Create album", R.drawable.ic_view_add) { AlbumActions.create(this@AlbumsFragment, vm) },
            LinearLayout.LayoutParams(0, root.dp(64), 1f))
        root.addView(shortcuts)
        val search = com.google.android.material.textfield.TextInputEditText(context).apply {
            hint = "Search albums"; isSingleLine = true;
            setPadding(dp(20), dp(8), dp(20), dp(8));
            background = android.graphics.drawable.GradientDrawable().apply { setColor(context.color(R.color.surface)); cornerRadius = dp(28).toFloat() }; setTextColor(context.color(R.color.text_primary))
        }
        root.addView(search, LinearLayout.LayoutParams(-1, root.dp(56)))
        val list = RecyclerView(context).apply { tag = "albums_list"; layoutManager = GridLayoutManager(context, 2); clipToPadding = false; setPadding(0, dp(12), 0, dp(16)) }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val adapter = AlbumAdapter({ album -> AlbumActions.open(this, album.id) }, { album -> AlbumActions.options(this, vm, album) })
        list.adapter = adapter
        search.addTextChangedListener { adapter.show(vm.albums.value.filter { album -> album.title.contains(it.toString(), true) }) }
        observeView = {
            viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.albums.collect { albums -> adapter.show(albums.filter { it.title.contains(search.text.toString(), true) }) }
            } }
        }
        return root
    }
    override fun onDestroyView() { (view as? ViewGroup)?.findViewWithTag<RecyclerView>("albums_list")?.adapter = null; observeView = null; super.onDestroyView() }
}

private class AlbumAdapter(val click: (AlbumCollection) -> Unit, val longClick: (AlbumCollection) -> Unit) : RecyclerView.Adapter<AlbumAdapter.Holder>() {
    private var items = emptyList<AlbumCollection>()
    fun show(next: List<AlbumCollection>) { items = next; notifyDataSetChanged() }
    class Holder(val card: MaterialCardView, val cover: ImageView, val title: TextView, val count: TextView) : RecyclerView.ViewHolder(card)
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
        val context = parent.context
        val card = MaterialCardView(context).apply {
            radius = dp(24).toFloat(); cardElevation = 0f; strokeWidth = 0
            setCardBackgroundColor(context.color(R.color.surface))
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(8), dp(4), dp(12)) }
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(context.color(R.color.accent_light)) }
        column.addView(image, LinearLayout.LayoutParams(-1, card.dp(156)))
        val title = TextView(context).apply { textSize = 16f; setTextColor(context.color(R.color.text_primary)); setPadding(dp(12), dp(12), dp(12), 0); maxLines = 2 }
        val count = TextView(context).apply { textSize = 12f; setTextColor(context.color(R.color.text_secondary)); setPadding(dp(12), dp(4), dp(12), dp(14)) }
        column.addView(title); column.addView(count); card.addView(column)
        return Holder(card, image, title, count)
    }
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val album = items[position]
        holder.title.text = album.title
        holder.count.text = "${album.photos.size} items" + if (album.automatic) " · Automatic" else ""
        val cover = album.photos.firstOrNull()
        holder.cover.scaleType = if (cover == null) ImageView.ScaleType.CENTER_INSIDE else ImageView.ScaleType.CENTER_CROP
        val padding = if (cover == null) holder.card.dp(40) else 0
        holder.cover.setPadding(padding, padding, padding, padding)
        holder.cover.load(cover?.thumbnailLink ?: cover?.uri ?: R.drawable.ic_album) {
            cover?.accountEmail?.let { setHeader("X-Photos-Account", it) }
            placeholder(R.drawable.ic_album); error(R.drawable.ic_album); size(500)
        }
        holder.card.contentDescription = "${album.title}, ${album.photos.size} items. Long press for album options"
        holder.card.setOnClickListener { click(album) }
        holder.card.setOnLongClickListener { longClick(album); true }
    }
}

object AlbumActions {
    fun open(fragment: Fragment, id: String) {
        val manager = fragment.requireActivity().supportFragmentManager
        if (!manager.isStateSaved) manager.beginTransaction().replace(R.id.fragment_container,
            AlbumDetailFragment().apply { arguments = Bundle().apply { putString("album_id", id) } })
            .addToBackStack(null).commit()
    }
    fun create(fragment: Fragment, vm: HomeViewModel, selected: Set<String> = emptySet()) {
        val input = EditText(fragment.requireContext()).apply { hint = "Album title"; isSingleLine = true; filters = arrayOf(android.text.InputFilter.LengthFilter(100)); setPadding(dp(24), dp(16), dp(24), dp(16)) }
        val dialog = MaterialAlertDialogBuilder(fragment.requireContext()).setTitle("Create album").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Create", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.toString().isBlank()) { input.error = "Enter an album title"; return@setOnClickListener }
                vm.createAlbum(input.text.toString(), selected) { id ->
                    vm.clearSelection()
                    if (fragment.isAdded) open(fragment, id)
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }
    fun choose(fragment: Fragment, vm: HomeViewModel, selected: Set<String>) {
        if (selected.isEmpty()) return
        val albums = vm.albums.value
        MaterialAlertDialogBuilder(fragment.requireContext()).setTitle("Add to album")
            .setItems((listOf("Create new album") + albums.map { it.title }).toTypedArray()) { _, position ->
                if (position == 0) create(fragment, vm, selected)
                else vm.viewModelScopeLaunchAdd(albums[position - 1].id, selected) {
                    if (fragment.isAdded) Toast.makeText(fragment.requireContext(), "Added to ${albums[position - 1].title}", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }
    fun options(fragment: Fragment, vm: HomeViewModel, album: AlbumCollection) {
        val action = if (album.automatic) "Hide album" else "Delete album"
        MaterialAlertDialogBuilder(fragment.requireContext()).setTitle(album.title)
            .setMessage(if (album.automatic) "Hide this automatic album? Photos stay in your library. You can restore automatic albums in Settings."
                else "Delete this album? Its photos and videos stay in your library.")
            .setNegativeButton("Cancel", null).setPositiveButton(action) { _, _ -> vm.deleteAlbum(album) }.show()
    }
}
