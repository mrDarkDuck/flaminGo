package com.example.mtchecker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
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
        val telegramBtn: Button = view.findViewById(R.id.telegramBtn) // Новая кнопка
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_proxy, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        
        val color = when {
            item.ping < 150 -> Color.parseColor("#4CAF50")
            item.ping < 400 -> Color.parseColor("#FFC107")
            else -> Color.parseColor("#F44336")
        }
        holder.pingText.text = "${item.displayName} | ${item.ping} ms"
        holder.pingText.setTextColor(color)
        
        // Копирование
        holder.copyBtn.setOnClickListener {
            val clipboard = it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Proxy", item.link)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(it.context, "Скопировано!", Toast.LENGTH_SHORT).show()
        }

        // Открытие в Telegram
        holder.telegramBtn.setOnClickListener {
            openProxyInTelegram(it.context, item.link)
        }
    }

    override fun getItemCount() = items.size

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

    // ФУНКЦИЯ ДЛЯ ОТКРЫТИЯ ССЫЛКИ В ТЕЛЕГРАМ
    private fun openProxyInTelegram(context: Context, link: String) {
        // Список пакетов популярных клиентов Telegram
        val telegramPackages = listOf(
            "org.telegram.messenger",      // Официальный
            "org.telegram.plus",           // Plus Messenger
            "com.radolyn.ayugram",         // AyuGram
            "tw.nekomimi.nekogram",        // Nekogram
            "org.thunderdog.challegram"    // Telegram X
        )

        var targetPackage: String? = null
        for (pkg in telegramPackages) {
            if (isAppInstalled(context, pkg)) {
                targetPackage = pkg
                break
            }
        }

        // Если в ссылке есть https://t.me/..., лучше заменить на tg:// для прямого открытия
        val deepLink = link.replace("https://t.me/", "tg://")

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Если нашли Telegram, открываем строго в нем
            targetPackage?.let { setPackage(it) }
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Telegram не найден на устройстве", Toast.LENGTH_LONG).show()
        }
    }

    // Вспомогательная функция проверки наличия приложения
    private fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
