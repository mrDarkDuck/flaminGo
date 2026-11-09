package com.example.mtchecker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
        holder.pingText.text = "${item.displayName} | ${item.ping} ms"
        
        holder.copyBtn.setOnClickListener {
            val clipboard = it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Proxy", item.link)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(it.context, "Copied!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount() = items.size

    fun addItem(item: ProxyItem) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }
}
