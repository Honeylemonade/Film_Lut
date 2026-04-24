package com.filmlut.android.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.filmlut.android.R
import com.filmlut.android.databinding.ItemPhotoBinding
import com.filmlut.android.model.PhotoItem

class PhotoStripAdapter(
    private val onSelected: (Int) -> Unit,
    private val onBatchChecked: (Int, Boolean) -> Unit,
) : RecyclerView.Adapter<PhotoStripAdapter.PhotoViewHolder>() {

    private val items = mutableListOf<PhotoItem>()
    private var activeIndex: Int = -1
    private val checkedIndices = linkedSetOf<Int>()

    fun submitList(newItems: List<PhotoItem>, selectedIndex: Int, checked: Set<Int>) {
        items.clear()
        items.addAll(newItems)
        activeIndex = selectedIndex
        checkedIndices.clear()
        checkedIndices.addAll(checked)
        notifyDataSetChanged()
    }

    fun updateSelection(index: Int) {
        val previous = activeIndex
        activeIndex = index
        if (previous >= 0) notifyItemChanged(previous)
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(items[position], position == activeIndex, checkedIndices.contains(position))
    }

    override fun getItemCount(): Int = items.size

    inner class PhotoViewHolder(
        private val binding: ItemPhotoBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PhotoItem, isActive: Boolean, isChecked: Boolean) {
            val context = binding.root.context
            binding.photoThumb.setImageBitmap(item.thumbnail)
            binding.photoLabel.text = item.label
            binding.photoFrame.foreground = null
            binding.photoFrame.backgroundTintList = null
            binding.photoFrame.background = ContextCompat.getDrawable(
                context,
                if (isActive) R.drawable.bg_editor_preview else R.drawable.bg_dark_thumb,
            )
            binding.photoFrame.alpha = if (isActive) 1f else 0.84f
            binding.root.alpha = if (isActive) 1f else 0.92f
            binding.root.setOnClickListener { onSelected(bindingAdapterPosition) }
            binding.photoCheck.setOnCheckedChangeListener(null)
            binding.photoCheck.isChecked = isChecked
            binding.photoCheck.setOnCheckedChangeListener { _, checked ->
                if (checked) checkedIndices.add(bindingAdapterPosition) else checkedIndices.remove(bindingAdapterPosition)
                onBatchChecked(bindingAdapterPosition, checked)
            }
        }
    }
}
