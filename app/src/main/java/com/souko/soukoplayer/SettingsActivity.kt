package com.souko.soukoplayer

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var etServerUrl: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnSave: Button
    private lateinit var btnBackToAlbum: Button
    private lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 绑定控件
        etServerUrl = findViewById(R.id.etServerUrl)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnSave = findViewById(R.id.btnSave)
        btnBackToAlbum = findViewById(R.id.btnBackToAlbum)

        // 初始化 ConfigManager
        configManager = ConfigManager

        // 读取已有配置显示在界面
        lifecycleScope.launch {
            etServerUrl.setText(configManager.getServerUrl(this@SettingsActivity))
            etUsername.setText(configManager.getUsername(this@SettingsActivity))
            etPassword.setText(configManager.getPassword(this@SettingsActivity))
        }

        // 保存按钮点击事件
        btnSave.setOnClickListener {
            val serverUrl = etServerUrl.text.toString().trim()
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (serverUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "请填写完整的服务器信息", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 保存配置
            lifecycleScope.launch {
                configManager.saveConfig(this@SettingsActivity, serverUrl, username, password)

                // 返回专辑列表并刷新
                setResult(RESULT_OK)
                finish()
            }
        }

        // 返回专辑列表按钮点击事件
        btnBackToAlbum.setOnClickListener {
            // 不保存配置，直接返回并刷新专辑列表
            setResult(RESULT_OK)
            finish()
        }
    }
}
