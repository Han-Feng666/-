package com.HanFeng.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.HanFeng.R
import com.HanFeng.databinding.ItemRuleDomainBinding
import com.HanFeng.databinding.ItemRuleGroupBinding
import com.HanFeng.model.RuleListItem
import com.HanFeng.model.RuleSource

class RuleListAdapter(
    private val onGroupClick: (String) -> Unit,
    private val onGroupLongPress: (String) -> Unit,
    private val onDomainClick: (RuleListItem.Domain) -> Unit,
    private val onDomainLongPress: (RuleListItem.Domain) -> Unit,
    private val onSelectionChanged: (RuleListItem.Domain, Boolean) -> Unit
) : ListAdapter<RuleListItem, RecyclerView.ViewHolder>(RuleItemDiffCallback()) {

    fun submit(items: List<RuleListItem>) {
        submitList(items)
    }

    override fun getItemViewType(position: Int): Int = if (getItem(position) is RuleListItem.Group) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            GroupHolder(ItemRuleGroupBinding.inflate(inflater, parent, false))
        } else {
            DomainHolder(ItemRuleDomainBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is RuleListItem.Group -> (holder as GroupHolder).bind(item)
            is RuleListItem.Domain -> (holder as DomainHolder).bind(item)
        }
    }

    inner class GroupHolder(private val binding: ItemRuleGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RuleListItem.Group) {
            binding.groupTitle.text = "${item.vendor} (${item.count})"
            binding.groupArrow.text = if (item.expanded) "▼" else "▶"
            binding.root.setOnClickListener { onGroupClick(item.vendor) }
            binding.root.setOnLongClickListener {
                onGroupLongPress(item.vendor)
                true
            }
        }
    }

    inner class DomainHolder(private val binding: ItemRuleDomainBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RuleListItem.Domain) {
            binding.domainText.text = item.rule.domain
            binding.sourceTag.text = item.rule.source.label
            binding.sourceTag.visibility = android.view.View.VISIBLE
            val color = if (item.rule.source == RuleSource.REFERENCE) R.color.hf_text_secondary else R.color.hf_text_primary
            binding.domainText.setTextColor(ContextCompat.getColor(binding.root.context, color))
            binding.selectBox.visibility = if (item.selectionMode) android.view.View.VISIBLE else android.view.View.GONE
            binding.root.alpha = if (item.selected) 0.78f else 1f
            binding.root.setOnClickListener {
                if (item.selectionMode) {
                    binding.selectBox.toggle()
                } else {
                    onDomainClick(item)
                }
            }
            binding.root.setOnLongClickListener {
                onDomainLongPress(item)
                true
            }
            binding.selectBox.setOnCheckedChangeListener(null)
            binding.selectBox.isChecked = item.selected
            binding.selectBox.setOnCheckedChangeListener { _, checked -> onSelectionChanged(item, checked) }
        }
    }

    private class RuleItemDiffCallback : DiffUtil.ItemCallback<RuleListItem>() {
        override fun areItemsTheSame(oldItem: RuleListItem, newItem: RuleListItem): Boolean {
            return when {
                oldItem is RuleListItem.Group && newItem is RuleListItem.Group -> oldItem.vendor == newItem.vendor
                oldItem is RuleListItem.Domain && newItem is RuleListItem.Domain -> oldItem.rule.id == newItem.rule.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: RuleListItem, newItem: RuleListItem): Boolean {
            return oldItem == newItem
        }
    }
}
