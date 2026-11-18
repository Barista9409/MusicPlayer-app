package com.example.ytmp3

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** 「曲を追加」シート。曲 / アルバム / プレイリストの URL を受け付ける */
class AddSheet : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.sheet_add, container, false)

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val layout = view.findViewById<TextInputLayout>(R.id.urlLayout)
        val url = view.findViewById<TextInputEditText>(R.id.url)
        val kind = view.findViewById<TextView>(R.id.kind)
        val whole = view.findViewById<MaterialSwitch>(R.id.wholeList)
        val makePl = view.findViewById<MaterialSwitch>(R.id.makePlaylist)
        val add = view.findViewById<View>(R.id.add)

        fun update(fromText: Boolean) {
            val u = url.text?.toString()?.trim().orEmpty()
            val valid = isYouTube(u)
            layout.error = if (u.isNotEmpty() && !valid) "YouTube / YouTube Music の URL を入力してください" else null
            val hasList = DownloadManager.hasListParam(u)
            if (fromText) whole.isChecked = DownloadManager.isListUrl(u)
            whole.visibility = if (valid && hasList) View.VISIBLE else View.GONE
            val listMode = valid && hasList && whole.isChecked
            makePl.visibility = if (listMode) View.VISIBLE else View.GONE
            kind.text = when {
                !valid -> ""
                listMode && u.contains("music.youtube.com") -> "YouTube Music のアルバム / プレイリスト(全曲)"
                listMode -> "プレイリスト(全曲)"
                else -> "1 曲"
            }
            add.isEnabled = valid
        }

        url.doAfterTextChanged { update(fromText = true) }
        whole.setOnCheckedChangeListener { _, _ -> update(fromText = false) }
        layout.setEndIconOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(requireContext())?.toString()
            if (text.isNullOrBlank()) toast("クリップボードが空です")
            else {
                url.setText(extractUrl(text))
                url.clearFocus()
                hideKeyboard(url)
            }
        }

        val given = arguments?.getString(ARG_URL)
        if (given != null) url.setText(extractUrl(given))
        else {
            // コピー済みの YouTube の URL があれば最初から入れておく
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(requireContext())?.toString() }.getOrNull()
            clip?.let { extractUrl(it) }?.takeIf { isYouTube(it) }?.let { url.setText(it) }
        }
        view.findViewById<View>(R.id.cancel).setOnClickListener { dismiss() }
        // キーボードの「完了」でもダウンロード
        url.setOnEditorActionListener { _, _, _ -> if (add.isEnabled) add.performClick(); true }
        update(fromText = true)

        add.setOnClickListener {
            val u = url.text?.toString()?.trim().orEmpty()
            if (!isYouTube(u)) return@setOnClickListener
            val listMode = DownloadManager.hasListParam(u) && whole.isChecked
            (activity as? MainActivity)?.ensureNotificationPermission()
            DownloadManager.add(requireContext(), u, wholeList = listMode, makePlaylist = listMode && makePl.isChecked)
            toast(if (listMode) "アルバム / プレイリストを取得しています…" else "ダウンロードを開始しました")
            dismiss()
        }
    }

    private fun hideKeyboard(v: View) {
        requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun isYouTube(u: String): Boolean {
        val l = u.lowercase()
        return (l.startsWith("http://") || l.startsWith("https://")) &&
            (l.contains("youtube.com/") || l.contains("youtu.be/"))
    }

    private fun extractUrl(text: String) = Regex("https?://\\S+").find(text)?.value ?: text.trim()

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val ARG_URL = "url"
        fun newInstance(url: String?) = AddSheet().apply { arguments = bundleOf(ARG_URL to url) }
    }
}
