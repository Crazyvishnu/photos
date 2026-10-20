package com.photosapp.ui.home

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.photosapp.R
import com.photosapp.di.ServiceLocator
import com.photosapp.data.MediaStateRepository
import com.photosapp.data.local.db.TrashEntry
import kotlinx.coroutines.*

class TrashFragment : Fragment() {
    private var entries=emptyList<TrashEntry>()
    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        val root=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(context.color(R.color.background)) }
        val bar=androidx.appcompat.widget.Toolbar(requireContext()).apply {
            title="Bin"; setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationIcon?.setTint(context.color(R.color.text_primary))
            setNavigationOnClickListener { parentFragmentManager.popBackStack() }
            menu.add("Restore all").setOnMenuItemClickListener {
                val restorable=entries.filter { !it.permanent }
                if(restorable.isNotEmpty()) MaterialAlertDialogBuilder(context).setTitle("Restore ${restorable.size} item(s)?")
                    .setMessage("Cloud items return after the account connects. Device-only items return immediately.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Restore") { _,_-> restore(restorable) }.show()
                true
            }
            menu.add("Empty Bin").setOnMenuItemClickListener {
                val items=entries.filter { !it.permanent }
                if(items.isNotEmpty()) DeleteMediaDialog.newInstance(items.map { it.entryKey }).show(parentFragmentManager,"delete_media")
                true
            }
        }
        root.addView(bar)
        root.addView(TextView(requireContext()).apply {
            text="Restore items or delete permanently. Bin hides device originals in Photos App; app cloud backups move to Drive Bin. Device originals are deleted only after confirmation. Drive may automatically remove cloud Bin items after 30 days."
            setPadding(dp(20),dp(12),dp(20),dp(12)); setTextColor(context.color(R.color.text_secondary))
        })
        root.addView(ListView(requireContext()),LinearLayout.LayoutParams(-1,0,1f))
        return root
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        val list=(view as ViewGroup).getChildAt(2) as ListView
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            ServiceLocator.db.libraryStateDao().observeTrash().collect { next ->
                entries=next
                list.adapter=object: BaseAdapter() {
                    override fun getCount()=maxOf(1,next.size)
                    override fun getItem(position: Int)=next.getOrNull(position)
                    override fun getItemId(position: Int)=position.toLong()
                    override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                        val item=next.getOrNull(position)
                        if(item==null) return TextView(parent.context).apply { text="Bin is empty"; gravity=Gravity.CENTER; setPadding(dp(20),dp(40),dp(20),dp(40)); setTextColor(context.color(R.color.text_secondary)) }
                        val row=LinearLayout(parent.context).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(8)) }
                        val cover=ImageView(parent.context).apply { scaleType=ImageView.ScaleType.CENTER_CROP; contentDescription=null; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                        row.addView(cover,LinearLayout.LayoutParams(row.dp(64),row.dp(64)).apply { marginEnd=row.dp(16) })
                        cover.load(item.thumbnailLink ?: item.uri.takeIf { !it.startsWith("http") } ?: R.drawable.ic_album) {
                            item.accountEmail?.let { setHeader("X-Photos-Account",it) }; size(160); error(R.drawable.ic_album)
                        }
                        row.addView(TextView(parent.context).apply {
                            val date=android.text.format.DateFormat.getMediumDateFormat(context).format(java.util.Date(item.capturedAt*1000))
                            val status=item.error ?: when(item.pendingAction) {
                                "TRASH" -> "Moving cloud backup to Bin…"
                                "RESTORE" -> "Restore pending · waiting for connection"
                                "DELETE" -> "Permanent cloud deletion pending"
                                else -> "Tap to restore or delete"
                            }
                            text=item.name+"\n"+date+"\n"+status
                            setTextColor(context.color(R.color.text_primary)); textSize=14f
                        },LinearLayout.LayoutParams(0,-2,1f))
                        return row
                    }
                }
                list.setOnItemClickListener { _,_,index,_ -> next.getOrNull(index)?.let { item ->
                    val actions=if(item.permanent) arrayOf("Retry cloud deletion") else arrayOf("Restore","Delete permanently")
                    MaterialAlertDialogBuilder(requireContext()).setTitle(item.name).setItems(actions) { _,which ->
                        if(!item.permanent && which==0) restore(listOf(item))
                        else if(item.permanent) com.photosapp.worker.MediaActionsWorker.enqueue(requireContext())
                        else DeleteMediaDialog.newInstance(listOf(item.entryKey)).show(parentFragmentManager,"delete_media")
                    }.show()
                } }
            }
        } }
    }
    private fun restore(items: List<TrashEntry>) {
        viewLifecycleOwner.lifecycleScope.launch {
            try { MediaStateRepository().restore(items) }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(isAdded) Toast.makeText(requireContext(),e.message ?: "Could not restore",Toast.LENGTH_LONG).show() }
        }
    }
    override fun onDestroyView() { (view as? ViewGroup)?.getChildAt(2)?.let { (it as? ListView)?.adapter=null }; entries=emptyList(); super.onDestroyView() }
    companion object {
        fun open(fragment: Fragment) { val fm=fragment.requireActivity().supportFragmentManager; if(!fm.isStateSaved) fm.beginTransaction().replace(R.id.fragment_container,TrashFragment()).addToBackStack(null).commit() }
    }
}
