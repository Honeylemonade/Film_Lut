package com.filmlut.android.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.filmlut.android.R
import com.filmlut.android.databinding.ItemLutBinding
import com.filmlut.android.model.LutSpec

class LutAdapter(
    private val onSelected: (LutSpec) -> Unit,
    private val onBatchChecked: (LutSpec, Boolean) -> Unit,
) : RecyclerView.Adapter<LutAdapter.LutViewHolder>() {

    private val items = mutableListOf<LutSpec>()
    private var selectedId: String? = null
    private val checkedIds = linkedSetOf<String>()

    fun submitList(newItems: List<LutSpec>, selected: LutSpec?, checked: Set<String>) {
        items.clear()
        items.addAll(newItems)
        selectedId = selected?.id
        checkedIds.clear()
        checkedIds.addAll(checked)
        notifyDataSetChanged()
    }

    fun updateSelection(selected: LutSpec?) {
        val previous = selectedId
        selectedId = selected?.id
        val previousIndex = items.indexOfFirst { it.id == previous }
        val newIndex = items.indexOfFirst { it.id == selectedId }
        if (previousIndex >= 0) notifyItemChanged(previousIndex)
        if (newIndex >= 0) notifyItemChanged(newIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LutViewHolder {
        val binding = ItemLutBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LutViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LutViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, item.id == selectedId, checkedIds.contains(item.id))
    }

    override fun getItemCount(): Int = items.size

    inner class LutViewHolder(
        private val binding: ItemLutBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: LutSpec, isSelected: Boolean, isChecked: Boolean) {
            val context = binding.root.context
            binding.nameText.text = item.name
            binding.categoryText.text = item.category
            binding.root.contentDescription =
                context.getString(R.string.lut_item_talkback, item.name, item.category)

            binding.card.strokeWidth = if (isSelected) 4 else 1
            binding.card.strokeColor =
                ContextCompat.getColor(context, if (isSelected) R.color.white else R.color.editor_line)
            binding.card.setCardBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (isSelected) R.color.editor_chip_bg else R.color.editor_panel_alt,
                )
            )
            binding.categoryText.isVisible = false
            binding.exportCheck.setOnCheckedChangeListener(null)
            binding.exportCheck.isChecked = isChecked
            binding.exportCheck.setOnCheckedChangeListener { _, checked ->
                if (checked) checkedIds.add(item.id) else checkedIds.remove(item.id)
                onBatchChecked(item, checked)
            }

            binding.root.setOnClickListener { onSelected(item) }
        }
    }
}
