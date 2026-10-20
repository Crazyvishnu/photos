package com.photosapp.ui.home

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.photosapp.R
import kotlinx.coroutines.launch

class AlbumDetailFragment : Fragment() {
    private val vm: HomeViewModel by activityViewModels { HomeViewModel.Factory }
    private var recycler: RecyclerView? = null
    private val albumId get() = requireArguments().getString("album_id").orEmpty()
    private val picking get() = arguments?.getBoolean("picking") == true
    private val chosen = linkedSetOf<String>()
    private var observeView: ((androidx.lifecycle.LifecycleOwner) -> Unit)? = null
    override fun onViewCreated(view: View, state: Bundle?) {
        observeView?.invoke(viewLifecycleOwner); observeView = null
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        chosen.addAll(state?.getStringArrayList("chosen").orEmpty())
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(context.color(R.color.background)) }
        val toolbar = androidx.appcompat.widget.Toolbar(context).apply {
            title = if (picking) "Choose photos" else "Album"
            setTitleTextColor(context.color(R.color.text_primary))
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationIcon?.setTint(context.color(R.color.text_primary))
            setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, root.dp(64)))
        val action = MaterialButton(context).apply { text = if (picking) "Add selected photos" else "Add photos" }
        root.addView(action, LinearLayout.LayoutParams(-1, root.dp(56)).apply { setMargins(root.dp(16), 0, root.dp(16), root.dp(8)) })
        val hint = TextView(context).apply { setTextColor(context.color(R.color.text_secondary)); setPadding(dp(20), dp(8), dp(20), dp(12)); textSize = 14f }
        root.addView(hint)
        val list = RecyclerView(context).apply { layoutManager = GridLayoutManager(context, 3) }
        recycler = list
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        lateinit var adapter: PhotoGridAdapter
        fun toggle(photo: LocalPhoto) {
            if (!chosen.add(photo.uri.toString())) chosen.remove(photo.uri.toString())
            list.post { if (list.adapter === adapter) adapter.notifyItemRangeChanged(0, adapter.itemCount) }
            action.text = "Add ${chosen.size} selected"
            action.isEnabled = chosen.isNotEmpty()
        }
        adapter = PhotoGridAdapter(onClick = { photo ->
            if (picking) toggle(photo) else if (!parentFragmentManager.isStateSaved) {
                val index = vm.photos.value.indexOfFirst { it.uri == photo.uri }
                parentFragmentManager.beginTransaction().replace(R.id.fragment_container,
                    PhotoViewerFragment.newInstance(index.coerceAtLeast(0), photo.uri.toString(), albumId)).addToBackStack(null).commit()
            }
        }, onLongClick = { photo ->
            if (picking) toggle(photo) else {
                val album = vm.albums.value.firstOrNull { it.id == albumId }
                if (album?.automatic != false) AlbumActions.choose(this, vm, setOf(photo.uri.toString()))
                else MaterialAlertDialogBuilder(context).setItems(arrayOf("Remove from album", "Add to another album")) { _, index ->
                    if (index == 0) vm.removeFromAlbum(albumId, photo) else AlbumActions.choose(this, vm, setOf(photo.uri.toString()))
                }.show()
            }
        }, isSelectionModeActive = { picking }, isSelected = { it.uri.toString() in chosen })
        list.adapter = adapter
        action.isEnabled = !picking || chosen.isNotEmpty()
        action.setOnClickListener {
            if (picking) {
                action.isEnabled = false
                vm.viewModelScopeLaunchAdd(albumId, chosen.toSet()) { if (isAdded && !parentFragmentManager.isStateSaved) parentFragmentManager.popBackStack() }
            } else if (!parentFragmentManager.isStateSaved) parentFragmentManager.beginTransaction().replace(R.id.fragment_container,
                AlbumDetailFragment().apply { arguments = Bundle().apply { putString("album_id", albumId); putBoolean("picking", true) } }).addToBackStack(null).commit()
        }
        observeView = {
            viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { com.photosapp.di.ServiceLocator.prefs.gridColumns.collect { columns -> (list.layoutManager as? GridLayoutManager)?.spanCount = columns } }
                launch { com.photosapp.di.ServiceLocator.prefs.showBackupBadges.collect { show ->
                    adapter.showBackupBadges = show
                    list.post { if (list.adapter === adapter) adapter.notifyItemRangeChanged(0, adapter.itemCount, "backup") }
                } }
                launch {
                    vm.albums.collect { albums ->
                        val album = albums.firstOrNull { it.id == albumId }
                        toolbar.title = if (picking) "Choose photos" else album?.title ?: "Album unavailable"
                        hint.text = when {
                            picking -> "Tap photos and videos to select them."
                            album == null -> "This album was removed."
                            album.id == "auto:documents" -> "Documents and scans, grouped by folder and filename. Add other document photos using Add photos."
                            album.photos.isEmpty() -> "No photos yet. Add some memories to this album."
                            else -> "${album.photos.size} items" + if (album.automatic) " · Automatically updated" else " · Long press a photo for options"
                        }
                        if (!picking) adapter.submitList(album?.photos.orEmpty().map { GridItem.Photo(it) })
                        action.isEnabled = album != null && (!picking || chosen.isNotEmpty())
                    }
                }
                if (picking) launch { vm.photos.collect { photos -> adapter.submitList(photos.map { GridItem.Photo(it) }) } }
            } }
        }
        return root
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putStringArrayList("chosen", ArrayList(chosen)); super.onSaveInstanceState(outState) }
    override fun onDestroyView() { recycler?.adapter = null; recycler = null; observeView = null; super.onDestroyView() }
}
