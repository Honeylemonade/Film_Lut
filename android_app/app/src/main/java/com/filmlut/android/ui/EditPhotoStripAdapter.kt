package com.filmlut.android.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.filmlut.android.databinding.ItemPhotoCompactBinding
import com.filmlut.android.model.PhotoItem

class EditPhotoStripAdapter(
    private val onSelected: (Int) -> Unit,
) : RecyclerView.Adapter<EditPhotoStripAdapter.CompactPhotoViewHolder>() {

    private val items = mutableListOf<PhotoItem>()
    private var activeIndex: Int = -1

    fun submitList(newItems: List<PhotoItem>, selectedIndex: Int) {
        items.clear()
        items.addAll(newItems)
        activeIndex = selectedIndex
        notifyDataSetChanged()
    }

    fun updateSelection(index: Int) {
        val previous = activeIndex
        activeIndex = index
        if (previous >= 0) notifyItemChanged(previous)
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CompactPhotoViewHolder {
        val binding = ItemPhotoCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CompactPhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CompactPhotoViewHolder, position: Int) {
        holder.bind(items[position], position == activeIndex)
    }

    override fun getItemCount(): Int = items.size

    inner class CompactPhotoViewHolder(
        private val binding: ItemPhotoCompactBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PhotoItem, isActive: Boolean) {
            binding.photoThumb.setImageBitmap(item.thumbnail)
            binding.selectionRing.visibility = if (isActive) View.VISIBLE else View.GONE
            binding.root.alpha = if (isActive) 1f else 0.78f
            binding.root.setOnClickListener { onSelected(bindingAdapterPosition) }
        }
    }
}
