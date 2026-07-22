package com.goai.ui.settings

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.goai.R
import com.goai.data.AppPreferences
import com.goai.data.LLMConfig
import com.goai.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupEngineSection()
        setupLLMSection()
        loadSavedValues()

        binding.btnSave.setOnClickListener {
            saveSettings()
            finish()
        }
    }

    /** 引擎设置 */
    private fun setupEngineSection() {
        val engineOptions = arrayOf("无引擎", "本地引擎", "云端引擎")
        val engineValues = arrayOf("none", "local", "cloud")
        binding.spinnerEngine.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, engineOptions)
        )
        binding.spinnerEngine.setOnClickListener {
            binding.spinnerEngine.showDropDown()
        }

        val boardSizeOptions = arrayOf("19x19", "13x13", "9x9")
        val boardSizeValues = intArrayOf(19, 13, 9)
        binding.spinnerBoardSize.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, boardSizeOptions)
        )
        binding.spinnerBoardSize.setOnClickListener {
            binding.spinnerBoardSize.showDropDown()
        }
    }

    /** LLM 讲解设置 */
    private fun setupLLMSection() {
        val llmOptions = AppPreferences.LLM_PRESETS.values.map { it.displayName }.toTypedArray()
        val llmKeys = AppPreferences.LLM_PRESETS.keys.toTypedArray()
        binding.spinnerLLM.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, llmOptions)
        )
        binding.spinnerLLM.setOnClickListener {
            binding.spinnerLLM.showDropDown()
        }

        // 选择服务商时自动填充默认值
        binding.spinnerLLM.setOnItemClickListener { _, _, position, _ ->
            val key = llmKeys[position]
            val config = AppPreferences.LLM_PRESETS[key]!!
            if (config.defaultUrl.isNotEmpty()) {
                binding.etLLMUrl.setText(config.defaultUrl)
            }
            if (config.defaultModel.isNotEmpty()) {
                binding.etLLMModel.setText(config.defaultModel)
            }
        }
    }

    /** 加载已保存的值 */
    private fun loadSavedValues() {
        // 引擎模式
        val engineModes = arrayOf("none", "local", "cloud")
        val engineIndex = engineModes.indexOf(prefs.engineMode)
        if (engineIndex >= 0) {
            binding.spinnerEngine.setText(binding.spinnerEngine.adapter.getItem(engineIndex) as String, false)
        }

        // 棋盘大小
        val boardSizes = intArrayOf(19, 13, 9)
        val boardIndex = boardSizes.indexOf(prefs.boardSize)
        if (boardIndex >= 0) {
            binding.spinnerBoardSize.setText(binding.spinnerBoardSize.adapter.getItem(boardIndex) as String, false)
        }

        // 贴目
        binding.etKomi.setText(prefs.komi.toString())

        // LLM
        val llmKeys = AppPreferences.LLM_PRESETS.keys.toTypedArray()
        val llmIndex = llmKeys.indexOf(prefs.llmProvider)
        if (llmIndex >= 0) {
            binding.spinnerLLM.setText(binding.spinnerLLM.adapter.getItem(llmIndex) as String, false)
        }
        binding.etLLMUrl.setText(prefs.llmUrl)
        binding.etLLMKey.setText(prefs.llmKey)
        binding.etLLMModel.setText(prefs.llmModel)
    }

    /** 保存设置 */
    private fun saveSettings() {
        // 引擎模式
        val engineText = binding.spinnerEngine.text.toString()
        val engineModes = arrayOf("无引擎", "本地引擎", "云端引擎")
        val engineValues = arrayOf("none", "local", "cloud")
        val engineIdx = engineModes.indexOf(engineText)
        if (engineIdx >= 0) prefs.engineMode = engineValues[engineIdx]

        // 棋盘大小
        val boardText = binding.spinnerBoardSize.text.toString()
        prefs.boardSize = when (boardText) {
            "13x13" -> 13
            "9x9" -> 9
            else -> 19
        }

        // 贴目
        binding.etKomi.text.toString().toFloatOrNull()?.let { prefs.komi = it }

        // LLM
        val llmText = binding.spinnerLLM.text.toString()
        val llmKeys = AppPreferences.LLM_PRESETS.keys.toTypedArray()
        val llmValues = AppPreferences.LLM_PRESETS.values.map { it.displayName }
        val llmIdx = llmValues.indexOf(llmText)
        if (llmIdx >= 0) prefs.llmProvider = llmKeys[llmIdx]

        prefs.llmUrl = binding.etLLMUrl.text.toString().trim()
        prefs.llmKey = binding.etLLMKey.text.toString().trim()
        prefs.llmModel = binding.etLLMModel.text.toString().trim()
    }
}
