package com.photosapp.ui.home

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.widget.*
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.photosapp.data.MediaTransferRepository
import kotlinx.coroutines.*

/** Bounded preview and copy editing: never overwrite the device or cloud original. */
class PhotoEditDialog : DialogFragment() {
    private val vm: HomeViewModel by activityViewModels { HomeViewModel.Factory }
    private var original: Bitmap? = null
    private var preview: Bitmap? = null
    private var rotation = 0
    private var square = false
    override fun onCreateDialog(state: Bundle?): Dialog {
        rotation=state?.getInt("rotation") ?: 0; square=state?.getBoolean("square") ?: false
        val photo=vm.photos.value.firstOrNull { it.uri.toString()==requireArguments().getString("uri") }
        val context=requireContext()
        val root=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(8)) }
        val image=ImageView(context).apply { scaleType=ImageView.ScaleType.FIT_CENTER; contentDescription="Edit preview" }
        root.addView(image,LinearLayout.LayoutParams(-1,root.dp(280)))
        root.addView(TextView(context).apply { text="Save an edited JPEG copy, up to 2048 pixels. Original date is kept; the original file stays unchanged."; setPadding(0,dp(8),0,dp(8)) })
        val row=LinearLayout(context)
        fun render() {
            val source=original ?: return
            val old=preview
            val size=minOf(source.width,source.height)
            val x=if(square) (source.width-size)/2 else 0
            val y=if(square) (source.height-size)/2 else 0
            preview=Bitmap.createBitmap(source,x,y,if(square) size else source.width,if(square) size else source.height,Matrix().apply { postRotate(rotation.toFloat()) },true)
            image.setImageBitmap(preview)
            if(old!=null && old!==original && old!==preview) old.recycle()
        }
        listOf("Rotate" to { rotation=(rotation+90)%360; render() }, "Square crop" to { square=!square; render() }, "Reset" to { rotation=0; square=false; render() }).forEach { (label,action) ->
            row.addView(com.google.android.material.button.MaterialButton(context,null,com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text=label; textSize=12f; minWidth=0; setPadding(0,0,0,0); setOnClickListener { action() }
            },LinearLayout.LayoutParams(0,root.dp(56),1f))
        }
        root.addView(row)
        val result=MaterialAlertDialogBuilder(context).setTitle("Edit photo").setView(root).setNegativeButton("Cancel",null).setPositiveButton("Save copy",null).create()
        result.setOnShowListener {
            val save=result.getButton(-1); save.isEnabled=false
            if(photo==null) { dismiss(); return@setOnShowListener }
            lifecycleScope.launch {
                try {
                    val request=ImageRequest.Builder(context).data(photo.uri).allowHardware(false).size(2048,2048).apply {
                        photo.accountEmail?.let { setHeader("X-Photos-Account",it) }
                    }.build()
                    val loaded=context.imageLoader.execute(request)
                    check(loaded is SuccessResult) { "Could not load photo" }
                    original=loaded.drawable.toBitmap().copy(Bitmap.Config.ARGB_8888,false)
                    render(); save.isEnabled=true
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { if(isAdded) { Toast.makeText(context,e.message ?: "Could not edit",Toast.LENGTH_LONG).show(); dismiss() } }
            }
            save.setOnClickListener {
                val bitmap=preview?.copy(Bitmap.Config.ARGB_8888,false) ?: return@setOnClickListener
                save.isEnabled=false; isCancelable=false
                lifecycleScope.launch {
                    try {
                        MediaTransferRepository().saveBitmap(bitmap,photo)
                        vm.loadPhotos()
                        if(isAdded) { Toast.makeText(context,"Edited copy saved",Toast.LENGTH_SHORT).show(); dismiss() }
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) { if(isAdded) { save.isEnabled=true; isCancelable=true; Toast.makeText(context,e.message ?: "Could not save",Toast.LENGTH_LONG).show() } }
                    finally { bitmap.recycle() }
                }
            }
        }
        return result
    }
    override fun onSaveInstanceState(state: Bundle) { state.putInt("rotation",rotation); state.putBoolean("square",square); super.onSaveInstanceState(state) }
    override fun onDestroyView() { preview?.takeIf { it!==original }?.recycle(); original?.recycle(); original=null; preview=null; super.onDestroyView() }
    companion object { fun newInstance(photo: LocalPhoto)=PhotoEditDialog().apply { arguments=Bundle().apply { putString("uri",photo.uri.toString()) } } }
}
