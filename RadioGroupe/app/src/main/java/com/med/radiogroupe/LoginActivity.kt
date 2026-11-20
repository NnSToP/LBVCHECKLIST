package com.med.radiogroupe

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Écran d'entrée : nom, groupe et mot de passe du groupe. */
class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("radiogroupe", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.parseColor("#F4F6F8"))
        }

        val title = TextView(this).apply {
            text = "RadioGroupe"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#12355B"))
        }
        val subtitle = TextView(this).apply {
            text = "Discussion de groupe chiffrée entre téléphones, sans internet"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(28))
        }

        val nameIn = field("Votre nom", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        nameIn.setText(prefs.getString("name", ""))
        val groupIn = field("Nom du groupe", InputType.TYPE_CLASS_TEXT)
        groupIn.setText(prefs.getString("group", ""))
        val passIn = field(
            "Mot de passe du groupe",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )

        val enter = Button(this).apply { text = "Entrer" }
        val help = TextView(this).apply {
            text = "Tous les membres doivent saisir le même nom de groupe et le même mot de passe."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#5A6472"))
            setPadding(0, dp(16), 0, 0)
        }

        enter.setOnClickListener {
            val name = nameIn.text.toString().trim()
            val group = groupIn.text.toString().trim()
            val pass = passIn.text.toString()
            when {
                name.length < 2 || name.length > 20 ->
                    toast("Le nom doit contenir entre 2 et 20 caractères")
                group.isEmpty() ->
                    toast("Saisissez le nom du groupe")
                pass.length < 6 ->
                    toast("Le mot de passe doit contenir au moins 6 caractères")
                else -> {
                    prefs.edit().putString("name", name).putString("group", group).apply()
                    val intent = Intent(this, ChatActivity::class.java)
                        .putExtra(ChatActivity.EXTRA_NAME, name)
                        .putExtra(ChatActivity.EXTRA_GROUP, group)
                        .putExtra(ChatActivity.EXTRA_PASS, pass)
                    passIn.setText("")
                    startActivity(intent)
                }
            }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(nameIn)
        root.addView(groupIn)
        root.addView(passIn)
        root.addView(enter)
        root.addView(help)
        setContentView(root)
    }

    private fun field(hintText: String, type: Int): EditText = EditText(this).apply {
        hint = hintText
        inputType = type
        setSingleLine(true)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
