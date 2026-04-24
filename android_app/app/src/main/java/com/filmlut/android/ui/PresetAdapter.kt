package com.filmlut.android.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.filmlut.android.databinding.ItemPresetBinding
import com.filmlut.android.model.FilmPreset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PresetAdapter(
    private val onSelected: (FilmPreset) -> Unit,
) : RecyclerView.Adapter<PresetAdapter.PresetViewHolder>() {
    private val items = mutableListOf<FilmPreset>()
    private val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun submitList(newItems: List<FilmPreset>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PresetViewHolder {
        val binding = ItemPresetBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PresetViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PresetViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class PresetViewHolder(
        private val binding: ItemPresetBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FilmPreset) {
            binding.presetName.text = item.name
            binding.presetMeta.text = binding.root.context.getString(
                com.filmlut.android.R.string.preset_meta_format,
                formatter.format(Date(item.createdAt)),
                item.adjustments.summary(),
            )
            binding.root.setOnClickListener { onSelected(item) }
        }
    }
}
