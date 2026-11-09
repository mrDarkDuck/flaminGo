package com.example.mtchecker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

data class ProxyItem(val link: String, val displayName: String, val ping: Long)

class ProxyAdapter(val items: MutableList<ProxyItem>) : RecyclerView.Adapter<ProxyAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val pingText: TextView = view.findViewById(R.id.pingText)
        val copyBtn: Button = view.findViewById(R.id.copyBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_proxy, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        
        // Раскрашиваем текст в зависимости от пинга
        val color = when {
            item.ping < 150 -> Color.parseColor("#4CAF50") // Зеленый (отлично)
            item.ping < 400 -> Color.parseColor("#FFC107") // Желтый (средне)
            else -> Color.parseColor("#F44336")            // Красный (медленно)
        }
        holder.pingText.text = "${item.displayName} | ${item.ping} ms"
        holder.pingText.setTextColor(color)
        
        holder.copyBtn.setOnClickListener {
            val clipboard = it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Proxy", item.link)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(it.context, "Copied!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount() = items.size

    // Умная вставка: ищем место, где пинг больше нашего, и вставляем туда
    fun addItem(item: ProxyItem) {
        val index = items.indexOfFirst { it.ping > item.ping }
        if (index == -1) {
            items.add(item)
            notifyItemInserted(items.size - 1)
        } else {
            items.add(index, item)
            notifyItemInserted(index)
        }
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }
}
