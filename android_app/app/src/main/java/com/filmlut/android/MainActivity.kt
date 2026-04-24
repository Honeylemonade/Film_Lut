package com.filmlut.android

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.filmlut.android.data.LutCatalogLoader
import com.filmlut.android.data.PresetStore
import com.filmlut.android.databinding.ActivityMainBinding
import com.filmlut.android.databinding.ItemControlGroupBinding
import com.filmlut.android.databinding.ItemControlSliderBinding
import com.filmlut.android.model.FilmAdjustments
import com.filmlut.android.model.FilmPreset
import com.filmlut.android.model.LutSpec
import com.filmlut.android.model.PhotoItem
import com.filmlut.android.processing.LutBitmapRenderer
import com.filmlut.android.processing.LutRepository
import com.filmlut.android.ui.LutAdapter
import com.filmlut.android.ui.EditPhotoStripAdapter
import com.filmlut.android.ui.PhotoStripAdapter
import com.filmlut.android.ui.PresetAdapter
import com.filmlut.android.util.BitmapIO
import com.google.android.material.slider.Slider
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var lutAdapter: LutAdapter
    private lateinit var photoAdapter: PhotoStripAdapter
    private lateinit var editPhotoAdapter: EditPhotoStripAdapter
    private lateinit var presetAdapter: PresetAdapter
    private lateinit var presetStore: PresetStore
    private lateinit var sessionStore: SharedPreferences

    private var lutItems: List<LutSpec> = emptyList()
    private var visibleLutItems: List<LutSpec> = emptyList()
    private var photoItems: List<PhotoItem> = emptyList()
    private var presets: List<FilmPreset> = emptyList()
    private var selectedLut: LutSpec? = null
    private val batchSelectedLutIds = linkedSetOf<String>()
    private val batchSelectedPhotoIndices = linkedSetOf<Int>()
    private var activePhotoIndex: Int = -1
    private var originalPreviewBitmap: Bitmap? = null
    private var filteredPreviewBitmap: Bitmap? = null
    private var showOriginal = false
    private val defaultAdjustments = FilmAdjustments()
    private var adjustments = defaultAdjustments
    private var previewJob: Job? = null
    private var lutPrewarmJob: Job? = null
    private val controlBindings = linkedMapOf<String, ItemControlSliderBinding>()
    private var selectedLutCategory: String = CATEGORY_ALL
    private var pendingSelectedLutId: String? = null
    private var pendingBatchLutIds: Set<String> = emptySet()

    private val controlSpecs = listOf(
        ControlSpec("lutIntensity", R.string.control_lut_intensity_title, R.string.control_lut_intensity_summary, 0, 100, { it.lutIntensity }) { state, value -> state.copy(lutIntensity = value) },
        ControlSpec("grain", R.string.control_grain_title, R.string.control_grain_summary, 0, 100, { it.grain }) { state, value -> state.copy(grain = value) },
        ControlSpec("dispersion", R.string.control_dispersion_title, R.string.control_dispersion_summary, 0, 100, { it.dispersion }) { state, value -> state.copy(dispersion = value) },
        ControlSpec("vignette", R.string.control_vignette_title, R.string.control_vignette_summary, 0, 100, { it.vignette }) { state, value -> state.copy(vignette = value) },
        ControlSpec("sharpen", R.string.control_sharpen_title, R.string.control_sharpen_summary, -100, 100, { it.sharpen }) { state, value -> state.copy(sharpen = value) },
        ControlSpec("clarity", R.string.control_clarity_title, R.string.control_clarity_summary, -100, 100, { it.clarity }) { state, value -> state.copy(clarity = value) },
        ControlSpec("rolloff", R.string.control_rolloff_title, R.string.control_rolloff_summary, 0, 100, { it.highlightRolloff }) { state, value -> state.copy(highlightRolloff = value) },
        ControlSpec("halation", R.string.control_halation_title, R.string.control_halation_summary, 0, 100, { it.halation }) { state, value -> state.copy(halation = value) },
        ControlSpec("bloom", R.string.control_bloom_title, R.string.control_bloom_summary, 0, 100, { it.bloom }) { state, value -> state.copy(bloom = value) },
        ControlSpec("shadowLift", R.string.control_shadow_lift_title, R.string.control_shadow_lift_summary, 0, 100, { it.shadowLift }) { state, value -> state.copy(shadowLift = value) },
        ControlSpec("toe", R.string.control_toe_title, R.string.control_toe_summary, 0, 100, { it.toe }) { state, value -> state.copy(toe = value) },
        ControlSpec("shoulder", R.string.control_shoulder_title, R.string.control_shoulder_summary, 0, 100, { it.shoulder }) { state, value -> state.copy(shoulder = value) },
        ControlSpec("highlightSaturation", R.string.control_highlight_sat_title, R.string.control_highlight_sat_summary, -100, 100, { it.highlightSaturation }) { state, value -> state.copy(highlightSaturation = value) },
        ControlSpec("shadowSaturation", R.string.control_shadow_sat_title, R.string.control_shadow_sat_summary, -100, 100, { it.shadowSaturation }) { state, value -> state.copy(shadowSaturation = value) },
        ControlSpec("highlightWarmth", R.string.control_highlight_warmth_title, R.string.control_highlight_warmth_summary, -100, 100, { it.highlightWarmth }) { state, value -> state.copy(highlightWarmth = value) },
        ControlSpec("shadowCoolness", R.string.control_shadow_coolness_title, R.string.control_shadow_coolness_summary, -100, 100, { it.shadowCoolness }) { state, value -> state.copy(shadowCoolness = value) },
    )

    private val controlGroups = listOf(
        ControlGroupSpec(
            R.string.control_group_basic_title,
            R.string.control_group_basic_summary,
            listOf("lutIntensity", "grain", "clarity"),
            true,
        ),
        ControlGroupSpec(
            R.string.control_group_optics_title,
            R.string.control_group_optics_summary,
            listOf("dispersion", "vignette", "sharpen"),
            false,
        ),
        ControlGroupSpec(
            R.string.control_group_tone_title,
            R.string.control_group_tone_summary,
            listOf("rolloff", "toe", "shoulder", "highlightSaturation", "shadowSaturation", "highlightWarmth", "shadowCoolness"),
            false,
        ),
        ControlGroupSpec(
            R.string.control_group_texture_title,
            R.string.control_group_texture_summary,
            listOf("halation", "bloom", "shadowLift"),
            false,
        ),
    )

    private val pickImagesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            uris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            handleImagesSelected(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        presetStore = PresetStore(this)
        sessionStore = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)

        restoreSessionScalars()
        setupUi()
        loadCatalog()
        loadPresets()
        if (!handleIncomingShare(intent)) {
            restoreSessionImages()
        }
    }

    override fun onStop() {
        persistSession()
        super.onStop()
    }

    override fun onDestroy() {
        previewJob?.cancel()
        lutPrewarmJob?.cancel()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    private fun setupUi() {
        lutAdapter = LutAdapter(::onLutSelected, ::onBatchLutChecked)
        photoAdapter = PhotoStripAdapter(::onPhotoSelected, ::onBatchPhotoChecked)
        editPhotoAdapter = EditPhotoStripAdapter(::onPhotoSelected)
        presetAdapter = PresetAdapter(::applyPreset)

        binding.lutRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = lutAdapter
        }
        binding.photoRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = photoAdapter
        }
        binding.editPhotoRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = editPhotoAdapter
        }
        binding.presetRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = presetAdapter
        }

        binding.pickImageButton.setOnClickListener { openPicker() }
        binding.reselectButton.setOnClickListener { openPicker() }
        binding.exportButton.setOnClickListener { exportCurrentBatch() }
        binding.batchExportButton.setOnClickListener { exportCurrentBatch() }
        binding.savePresetButton.setOnClickListener { saveCurrentPreset() }
        binding.resetAdjustmentsButton.setOnClickListener { resetAdjustments() }
        binding.compareButton.setOnTouchListener { _, event -> handleCompareTouch(event) }
        binding.fullPreviewCompareButton.setOnTouchListener { _, event -> handleCompareTouch(event) }
        binding.previewSurface.setOnClickListener {
            if (originalPreviewBitmap == null) openPicker() else openFullPreview()
        }
        binding.closeFullPreviewButton.setOnClickListener { binding.fullPreviewOverlay.visibility = View.GONE }
        binding.editToolToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.toolLutButton -> switchEditorPanel(EditorPanel.LUT)
                R.id.toolFilmButton -> switchEditorPanel(EditorPanel.FILM)
            }
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_edit -> switchTab(Tab.EDIT)
                R.id.nav_batch -> switchTab(Tab.BATCH)
            }
            true
        }
        binding.bottomNav.selectedItemId = R.id.nav_edit
        binding.editToolToggleGroup.check(R.id.toolLutButton)
        binding.compareButton.visibility = View.GONE
        binding.previewTapHint.visibility = View.GONE

        setupFilmControls()
        refreshPhotoSummary()
    }

    private fun setupFilmControls() {
        val inflater = LayoutInflater.from(this)
        controlBindings.clear()
        val controlMap = controlSpecs.associateBy { it.key }
        controlGroups.forEach { group ->
            val groupBinding = ItemControlGroupBinding.inflate(inflater, binding.filmControlsContainer, false)
            groupBinding.groupTitle.setText(group.titleRes)
            groupBinding.groupSummary.setText(group.summaryRes)
            groupBinding.groupAction.setText(if (group.expandedByDefault) R.string.group_collapse else R.string.group_expand)
            groupBinding.groupControls.visibility = if (group.expandedByDefault) View.VISIBLE else View.GONE

            group.keys.mapNotNull(controlMap::get).forEach { spec ->
                val controlBinding = ItemControlSliderBinding.inflate(inflater, groupBinding.groupControls, false)
                bindControl(controlBinding, spec)
                groupBinding.groupControls.addView(controlBinding.root)
                controlBindings[spec.key] = controlBinding
            }

            groupBinding.groupHeader.setOnClickListener {
                val expanded = groupBinding.groupControls.visibility == View.VISIBLE
                groupBinding.groupControls.visibility = if (expanded) View.GONE else View.VISIBLE
                groupBinding.groupAction.setText(if (expanded) R.string.group_expand else R.string.group_collapse)
            }
            binding.filmControlsContainer.addView(groupBinding.root)
        }
        updateAdjustmentDigest()
    }

    private fun bindControl(controlBinding: ItemControlSliderBinding, spec: ControlSpec) {
        controlBinding.controlTitle.setText(spec.titleRes)
        controlBinding.controlSummary.setText(spec.summaryRes)
        controlBinding.controlSlider.valueFrom = spec.min.toFloat()
        controlBinding.controlSlider.valueTo = spec.max.toFloat()
        controlBinding.controlSlider.stepSize = 1f
        controlBinding.controlSlider.value = spec.getter(adjustments).toFloat()
        controlBinding.controlValue.text = spec.getter(adjustments).toString()
        controlBinding.controlSlider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            val intValue = value.toInt()
            controlBinding.controlValue.text = intValue.toString()
            if (fromUser) {
                adjustments = spec.updater(adjustments, intValue)
                updateAdjustmentDigest()
                persistSession()
                schedulePreviewRender()
            }
        }
    }

    private fun switchTab(tab: Tab) {
        binding.editTab.visibility = if (tab == Tab.EDIT) View.VISIBLE else View.GONE
        binding.batchTab.visibility = if (tab == Tab.BATCH) View.VISIBLE else View.GONE
    }

    private fun switchEditorPanel(panel: EditorPanel) {
        binding.lutsTab.visibility = if (panel == EditorPanel.LUT) View.VISIBLE else View.GONE
        binding.filmTab.visibility = if (panel == EditorPanel.FILM) View.VISIBLE else View.GONE
    }

    private fun openPicker() {
        pickImagesLauncher.launch(arrayOf("image/*"))
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingShare(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return false

        val uris = parseSharedUris(intent.extras?.get(Intent.EXTRA_STREAM))
            .ifEmpty {
                listOfNotNull(
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                        ?: intent.getStringExtra(Intent.EXTRA_STREAM)?.let(Uri::parse),
                )
            }
            .ifEmpty {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            }
            .distinct()

        if (uris.isEmpty()) return false
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        handleImagesSelected(uris)
        return true
    }

    private fun parseSharedUris(value: Any?): List<Uri> {
        return when (value) {
            is Uri -> listOf(value)
            is String -> value.split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(Uri::parse)
            is ArrayList<*> -> value.mapNotNull(::parseSharedUriItem)
            is Array<*> -> value.mapNotNull(::parseSharedUriItem)
            else -> emptyList()
        }
    }

    private fun parseSharedUriItem(value: Any?): Uri? {
        return when (value) {
            is Uri -> value
            is String -> value.trim().takeIf(String::isNotEmpty)?.let(Uri::parse)
            else -> null
        }
    }

    private fun loadCatalog() {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { LutCatalogLoader.load(this@MainActivity) }
            }.onSuccess { items ->
                lutItems = items
                selectedLut = pendingSelectedLutId
                    ?.let { id -> items.firstOrNull { it.id == id } }
                    ?: selectedLut
                    ?: items.firstOrNull()
                batchSelectedLutIds.clear()
                val validBatchIds = pendingBatchLutIds.filterTo(linkedSetOf()) { id ->
                    items.any { it.id == id }
                }
                if (validBatchIds.isNotEmpty()) {
                    batchSelectedLutIds.addAll(validBatchIds)
                } else {
                    selectedLut?.id?.let { batchSelectedLutIds.add(it) }
                }
                setupLutCategoryChips(items)
                applyLutCategoryFilter()
                syncSelectedLutInfo()
                refreshExportButton()
                prewarmCurrentLuts()
                if (originalPreviewBitmap != null && filteredPreviewBitmap == null) {
                    schedulePreviewRender(immediate = true)
                }
            }.onFailure {
                showToast(getString(R.string.catalog_load_failed))
            }
        }
    }

    private fun loadPresets() {
        presets = presetStore.load().sortedByDescending { it.createdAt }
        presetAdapter.submitList(presets)
        binding.presetSummary.text = if (presets.isEmpty()) {
            getString(R.string.preset_empty)
        } else {
            getString(R.string.preset_section_subtitle)
        }
        binding.filmTabSubtitle.text = getString(R.string.film_tab_subtitle_short)
        refreshProjectSummary()
    }

    private fun handleImagesSelected(
        uris: List<Uri>,
        restoredActiveIndex: Int = 0,
        restoredBatchIndices: Set<Int>? = null,
        shouldPersist: Boolean = true,
    ) {
        lifecycleScope.launch {
            showProgress(getString(R.string.status_processing))
            runCatching {
                withContext(Dispatchers.IO) {
                    uris.mapIndexed { index, uri ->
                        PhotoItem(
                            uri = uri,
                            thumbnail = BitmapIO.decodeBitmap(this@MainActivity, uri, maxEdge = 220),
                            label = BitmapIO.displayName(this@MainActivity, uri).ifBlank {
                                getString(R.string.photo_item_label, index + 1)
                            },
                        )
                    }
                }
            }.onSuccess { items ->
                photoItems = items
                activePhotoIndex = if (items.isEmpty()) {
                    -1
                } else {
                    restoredActiveIndex.coerceIn(items.indices)
                }
                batchSelectedPhotoIndices.clear()
                val restoredValidIndices = restoredBatchIndices
                    ?.filterTo(linkedSetOf()) { it in photoItems.indices }
                    .orEmpty()
                if (restoredValidIndices.isNotEmpty()) {
                    batchSelectedPhotoIndices.addAll(restoredValidIndices)
                } else {
                    photoItems.indices.forEach { batchSelectedPhotoIndices.add(it) }
                }
                photoAdapter.submitList(photoItems, activePhotoIndex, batchSelectedPhotoIndices)
                editPhotoAdapter.submitList(photoItems, activePhotoIndex)
                binding.editToolToggleGroup.check(R.id.toolLutButton)
                switchEditorPanel(EditorPanel.LUT)
                refreshPhotoSummary()
                if (shouldPersist) persistSession()
                loadActivePhotoPreview()
            }.onFailure {
                hideProgress()
                binding.statusText.text = getString(R.string.image_load_failed)
                showToast(getString(R.string.image_load_failed))
            }
        }
    }

    private fun onPhotoSelected(index: Int) {
        if (index !in photoItems.indices || activePhotoIndex == index) return
        activePhotoIndex = index
        photoAdapter.updateSelection(index)
        editPhotoAdapter.updateSelection(index)
        refreshPhotoSummary()
        persistSession()
        loadActivePhotoPreview()
    }

    private fun onBatchPhotoChecked(index: Int, checked: Boolean) {
        if (checked) batchSelectedPhotoIndices.add(index) else batchSelectedPhotoIndices.remove(index)
        refreshPhotoSummary()
        persistSession()
    }

    private fun loadActivePhotoPreview() {
        val item = photoItems.getOrNull(activePhotoIndex)
        if (item == null) {
            originalPreviewBitmap = null
            filteredPreviewBitmap = null
            binding.previewPlaceholder.visibility = View.VISIBLE
            binding.previewImage.setImageDrawable(null)
            binding.exportButton.isEnabled = false
            binding.compareButton.isEnabled = false
            binding.compareButton.visibility = View.GONE
            binding.fullPreviewCompareButton.isEnabled = false
            binding.previewTapHint.visibility = View.GONE
            hideProgress()
            return
        }

        lifecycleScope.launch {
            showProgress(getString(R.string.status_processing))
            runCatching {
                withContext(Dispatchers.IO) { BitmapIO.decodeBitmap(this@MainActivity, item.uri, maxEdge = 900) }
            }.onSuccess { bitmap ->
                originalPreviewBitmap = bitmap
                binding.previewPlaceholder.visibility = View.GONE
                binding.previewTapHint.visibility = View.GONE
                binding.compareButton.visibility = View.VISIBLE
                binding.previewImage.setImageBitmap(bitmap)
                binding.compareButton.isEnabled = selectedLut != null
                binding.fullPreviewCompareButton.isEnabled = selectedLut != null
                binding.statusText.text = getString(R.string.preview_ready)
                schedulePreviewRender(immediate = true)
            }.onFailure {
                hideProgress()
                binding.statusText.text = getString(R.string.image_load_failed)
                showToast(getString(R.string.image_load_failed))
            }
        }
    }

    private fun onLutSelected(lut: LutSpec) {
        selectedLut = lut
        lutAdapter.updateSelection(lut)
        syncSelectedLutInfo()
        refreshExportButton()
        updateAdjustmentDigest()
        persistSession()
        prewarmCurrentLuts()
        schedulePreviewRender()
    }

    private fun onBatchLutChecked(lut: LutSpec, checked: Boolean) {
        if (checked) {
            batchSelectedLutIds.add(lut.id)
        } else {
            batchSelectedLutIds.remove(lut.id)
        }
        refreshExportButton()
        persistSession()
        prewarmCurrentLuts()
    }

    private fun applyPreset(preset: FilmPreset) {
        selectedLut = lutItems.firstOrNull { it.id == preset.lutId } ?: selectedLut
        selectedLut?.let { lutAdapter.updateSelection(it) }
        adjustments = preset.adjustments
        syncControlViews()
        syncSelectedLutInfo()
        updateAdjustmentDigest()
        persistSession()
        prewarmCurrentLuts()
        schedulePreviewRender(immediate = true)
        showToast(getString(R.string.preset_applied, preset.name))
    }

    private fun saveCurrentPreset() {
        val lut = selectedLut ?: run {
            showToast(getString(R.string.choose_image_first))
            return
        }
        val preset = FilmPreset(
            name = getString(R.string.preset_default_name, presets.size + 1),
            lutId = lut.id,
            adjustments = adjustments,
            createdAt = System.currentTimeMillis(),
        )
        presets = (listOf(preset) + presets).take(24)
        presetStore.saveAll(presets)
        presetAdapter.submitList(presets)
        binding.presetSummary.text = getString(R.string.preset_section_subtitle)
        showToast(getString(R.string.preset_saved, preset.name))
    }

    private fun syncSelectedLutInfo() {
        binding.categoryChip.visibility = View.GONE
        val meta = if (photoItems.isNotEmpty() && activePhotoIndex in photoItems.indices) {
            getString(
                R.string.photo_meta_format,
                activePhotoIndex + 1,
                photoItems.size,
                getString(R.string.tap_to_zoom_hint),
            )
        } else {
            getString(R.string.preview_hint_short)
        }
        binding.selectedPhotoMeta.text = meta
        binding.selectedPhotoMeta.visibility = View.GONE
        binding.lutSectionSubtitle.text =
            getString(R.string.lut_section_subtitle, visibleLutItems.size)
        refreshProjectSummary()
    }

    private fun refreshPhotoSummary() {
        val count = photoItems.size
        binding.photoCountChip.text = when (count) {
            0 -> getString(R.string.no_photos_selected)
            1 -> getString(R.string.photo_count_single)
            else -> getString(R.string.photo_count_multiple, count)
        }
        binding.photosTabSubtitle.text = getString(R.string.photos_tab_subtitle)
        binding.photoBatchSummary.text = getString(R.string.photo_batch_summary, exportPhotos().size)
        refreshExportButton()
        syncSelectedLutInfo()
        refreshProjectSummary()
        photoAdapter.submitList(photoItems, activePhotoIndex, batchSelectedPhotoIndices)
        editPhotoAdapter.submitList(photoItems, activePhotoIndex)
        val showPhotoRail = photoItems.size > 1
        binding.editPhotoRail.visibility = if (showPhotoRail) View.VISIBLE else View.GONE
        binding.editPhotoRecyclerView.visibility = if (showPhotoRail) View.VISIBLE else View.GONE
    }

    private fun schedulePreviewRender(immediate: Boolean = false) {
        val source = originalPreviewBitmap ?: return
        val lut = selectedLut ?: return
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            if (!immediate) delay(150)
            setPreviewMode(false)
            showProgress(getString(R.string.status_processing))
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    LutBitmapRenderer.render(
                        source,
                        LutRepository.get(this@MainActivity, lut.lutAssetPath),
                        adjustments,
                        LutBitmapRenderer.RenderQuality.PREVIEW,
                    )
                }
            }
            result.onSuccess { bitmap ->
                filteredPreviewBitmap = bitmap
                setPreviewMode(false)
                binding.compareButton.isEnabled = true
                binding.fullPreviewCompareButton.isEnabled = true
                refreshExportButton()
                binding.statusText.text = getString(R.string.status_done)
            }.onFailure {
                binding.statusText.text = getString(R.string.export_failed)
                showToast(it.message ?: getString(R.string.export_failed))
            }
            hideProgress()
        }
    }

    private fun prewarmCurrentLuts() {
        if (lutItems.isEmpty()) return
        val assetPaths = buildList {
            selectedLut?.lutAssetPath?.let(::add)
            exportLuts().forEach { add(it.lutAssetPath) }
        }.distinct().take(4)
        if (assetPaths.isEmpty()) return
        lutPrewarmJob?.cancel()
        lutPrewarmJob = lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                LutRepository.warm(this@MainActivity, assetPaths)
            }
        }
    }

    private fun handleCompareTouch(event: MotionEvent): Boolean {
        if (filteredPreviewBitmap == null || originalPreviewBitmap == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                setPreviewMode(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                setPreviewMode(false)
                return true
            }
        }
        return false
    }

    private fun setPreviewMode(showOriginalNow: Boolean) {
        val original = originalPreviewBitmap
        val filtered = filteredPreviewBitmap
        showOriginal = showOriginalNow
        val bitmap = when {
            showOriginalNow && original != null -> original
            filtered != null -> filtered
            original != null -> original
            else -> null
        }
        if (bitmap == null) {
            binding.previewImage.setImageDrawable(null)
            binding.fullPreviewImage.setImageDrawable(null)
        } else {
            binding.previewImage.setImageBitmap(bitmap)
            if (binding.fullPreviewOverlay.visibility == View.VISIBLE) {
                binding.fullPreviewImage.setImageBitmapPreservingViewport(bitmap)
            }
        }
        binding.compareButton.contentDescription = getString(
            if (showOriginalNow) R.string.compare_filtered else R.string.compare_original,
        )
        binding.fullPreviewCompareButton.text = getString(
            if (showOriginalNow) R.string.compare_filtered else R.string.compare_original,
        )
    }

    private fun resetAdjustments() {
        adjustments = defaultAdjustments
        syncControlViews()
        updateAdjustmentDigest()
        persistSession()
        showToast(getString(R.string.adjustment_reset_done))
        schedulePreviewRender(immediate = true)
    }

    private fun openFullPreview() {
        val bitmap = if (showOriginal) originalPreviewBitmap else filteredPreviewBitmap ?: originalPreviewBitmap
        if (bitmap == null) {
            showToast(getString(R.string.choose_image_first))
            return
        }
        binding.fullPreviewImage.setImageBitmap(bitmap)
        binding.fullPreviewCompareButton.isEnabled = filteredPreviewBitmap != null
        binding.fullPreviewCompareButton.text = getString(R.string.compare_original)
        binding.fullPreviewOverlay.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.full_preview_hint)
    }

    private fun exportCurrentBatch() {
        val photos = exportPhotos()
        val exportLuts = exportLuts()
        if (photos.isEmpty() || exportLuts.isEmpty()) {
            showToast(getString(R.string.choose_image_first))
            return
        }

        lifecycleScope.launch {
            showProgress(getString(R.string.status_exporting))
            runCatching {
                val total = photos.size * exportLuts.size
                var done = 0
                exportLuts.forEach { lut ->
                    photos.forEach { photo ->
                        done += 1
                        withContext(Dispatchers.Main) {
                            binding.progressLabel.text =
                                getString(R.string.batch_export_progress_lut, lut.name, done, total)
                        }
                        val exportBitmap = withContext(Dispatchers.IO) {
                            BitmapIO.decodeBitmap(this@MainActivity, photo.uri, maxEdge = 2200)
                        }
                        val rendered = withContext(Dispatchers.Default) {
                            LutBitmapRenderer.render(
                                exportBitmap,
                                LutRepository.get(this@MainActivity, lut.lutAssetPath),
                                adjustments,
                            )
                        }
                        withContext(Dispatchers.IO) {
                            BitmapIO.saveBitmap(this@MainActivity, rendered, lut.name)
                        }
                    }
                }
            }.onSuccess {
                binding.statusText.text = getString(R.string.export_success)
                showToast(getString(R.string.export_success))
            }.onFailure {
                binding.statusText.text = getString(R.string.export_failed)
                showToast(it.message ?: getString(R.string.export_failed))
            }
            hideProgress()
        }
    }

    private fun showProgress(label: String) {
        binding.progressLabel.text = label
        binding.progressOverlay.visibility = View.VISIBLE
    }

    private fun hideProgress() {
        binding.progressOverlay.visibility = View.GONE
    }

    private fun exportPhotos(): List<PhotoItem> {
        val checked = photoItems.filterIndexed { index, _ -> batchSelectedPhotoIndices.contains(index) }
        return if (checked.isNotEmpty()) checked else photoItems
    }

    private fun exportLuts(): List<LutSpec> {
        val checked = lutItems.filter { batchSelectedLutIds.contains(it.id) }
        if (checked.isNotEmpty()) return checked
        return selectedLut?.let { listOf(it) } ?: emptyList()
    }

    private fun refreshExportButton() {
        val lutCount = exportLuts().size
        val photoCount = exportPhotos().size
        binding.heroOutputChip.text = getString(R.string.export_combo_summary, photoCount, lutCount)
        binding.exportButton.contentDescription = if (photoCount > 1 && lutCount > 1) {
            getString(R.string.export_batch_button_combo, photoCount, lutCount)
        } else if (lutCount <= 1) {
            getString(R.string.export_batch_button_single)
        } else {
            getString(R.string.export_batch_button_multi, lutCount)
        }
        val canExport = photoItems.isNotEmpty() && filteredPreviewBitmap != null && lutCount > 0 && photoCount > 0
        binding.exportButton.isEnabled = canExport
        binding.batchExportButton.isEnabled = canExport
        binding.batchExportButton.text = if (canExport) {
            getString(R.string.batch_export_cta, photoCount, lutCount)
        } else {
            getString(R.string.export_batch)
        }
    }

    private fun setupLutCategoryChips(items: List<LutSpec>) {
        val categories = listOf(CATEGORY_ALL) + items.map { it.category.trim().ifBlank { "其他" } }.distinct()
        binding.lutCategoryGroup.removeAllViews()
        categories.forEach { category ->
            val chip = Chip(this).apply {
                text = category
                isCheckable = true
                isCheckedIconVisible = false
                checkedIcon = null
                chipStrokeWidth = 0f
                setEnsureMinTouchTargetSize(false)
                setChipBackgroundColorResource(R.color.editor_chip_bg)
                setTextColor(getColorStateList(R.color.nav_item_colors_dark))
                isChecked = category == selectedLutCategory
                setOnClickListener {
                    selectedLutCategory = category
                    applyLutCategoryFilter()
                }
            }
            binding.lutCategoryGroup.addView(chip)
        }
    }

    private fun applyLutCategoryFilter() {
        visibleLutItems = if (selectedLutCategory == CATEGORY_ALL) {
            lutItems
        } else {
            lutItems.filter { it.category.trim().ifBlank { "其他" } == selectedLutCategory }
        }
        if (selectedLut != null && visibleLutItems.none { it.id == selectedLut?.id }) {
            selectedLut = visibleLutItems.firstOrNull() ?: lutItems.firstOrNull()
        }
        lutAdapter.submitList(visibleLutItems, selectedLut, batchSelectedLutIds)
        syncSelectedLutInfo()
    }

    private fun syncControlViews() {
        controlSpecs.forEach { spec ->
            val controlBinding = controlBindings[spec.key] ?: return@forEach
            val value = spec.getter(adjustments)
            controlBinding.controlSlider.value = value.toFloat()
            controlBinding.controlValue.text = value.toString()
        }
    }

    private fun updateAdjustmentDigest() {
        val lutName = selectedLut?.name
        binding.adjustmentDigest.text = if (lutName == null) {
            getString(R.string.adjustment_digest_empty)
        } else {
            getString(R.string.adjustment_digest_format, lutName, adjustments.summary())
        }
    }

    private fun refreshProjectSummary() {
        val selectedPhotos = exportPhotos().size
        val selectedLuts = exportLuts().size
        if (photoItems.isEmpty()) {
            binding.projectTitle.text = getString(R.string.project_empty_title)
            binding.projectMeta.text = getString(R.string.project_empty_meta)
            binding.projectFocus.text = getString(R.string.project_focus_empty)
            return
        }
        binding.projectTitle.text = if (selectedPhotos > 0 && selectedLuts > 0) {
            getString(R.string.tag_batch_ready)
        } else {
            getString(R.string.section_project)
        }
        binding.projectMeta.text = getString(
            R.string.project_meta_format,
            photoItems.size,
            selectedPhotos,
            selectedLuts,
        )
        val activeLabel = photoItems.getOrNull(activePhotoIndex)?.label ?: getString(R.string.status_no_image)
        binding.projectFocus.text = getString(R.string.project_focus_format, activeLabel)
    }

    private fun restoreSessionScalars() {
        pendingSelectedLutId = sessionStore.getString(KEY_SELECTED_LUT_ID, null)
        pendingBatchLutIds = jsonArrayToStringSet(sessionStore.getString(KEY_BATCH_LUT_IDS, null))
        adjustments = adjustmentsFromJson(sessionStore.getString(KEY_ADJUSTMENTS, null)) ?: defaultAdjustments
    }

    private fun restoreSessionImages() {
        val uris = jsonArrayToStringList(sessionStore.getString(KEY_PHOTO_URIS, null))
            .map(Uri::parse)
            .distinct()
        if (uris.isEmpty()) return
        val activeIndex = sessionStore.getInt(KEY_ACTIVE_PHOTO_INDEX, 0)
        val batchIndices = jsonArrayToIntSet(sessionStore.getString(KEY_BATCH_PHOTO_INDICES, null))
        handleImagesSelected(
            uris = uris,
            restoredActiveIndex = activeIndex,
            restoredBatchIndices = batchIndices,
            shouldPersist = false,
        )
    }

    private fun persistSession() {
        if (!::sessionStore.isInitialized) return
        val photoUris = JSONArray().apply {
            photoItems.forEach { put(it.uri.toString()) }
        }
        val batchPhotoIndices = JSONArray().apply {
            batchSelectedPhotoIndices.sorted().forEach(::put)
        }
        val batchLuts = JSONArray().apply {
            batchSelectedLutIds.forEach(::put)
        }
        sessionStore.edit()
            .putString(KEY_PHOTO_URIS, photoUris.toString())
            .putInt(KEY_ACTIVE_PHOTO_INDEX, activePhotoIndex)
            .putString(KEY_BATCH_PHOTO_INDICES, batchPhotoIndices.toString())
            .putString(KEY_SELECTED_LUT_ID, selectedLut?.id ?: pendingSelectedLutId)
            .putString(KEY_BATCH_LUT_IDS, batchLuts.toString())
            .putString(KEY_ADJUSTMENTS, adjustmentsToJson(adjustments).toString())
            .apply()
    }

    private fun jsonArrayToStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.optString(index) }
                .filter(String::isNotBlank)
        }.getOrDefault(emptyList())
    }

    private fun jsonArrayToStringSet(raw: String?): Set<String> {
        return jsonArrayToStringList(raw).toSet()
    }

    private fun jsonArrayToIntSet(raw: String?): Set<Int> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.optInt(index, -1) }
                .filter { it >= 0 }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun adjustmentsToJson(value: FilmAdjustments): JSONObject {
        return JSONObject()
            .put("lutIntensity", value.lutIntensity)
            .put("grain", value.grain)
            .put("dispersion", value.dispersion)
            .put("vignette", value.vignette)
            .put("sharpen", value.sharpen)
            .put("clarity", value.clarity)
            .put("highlightRolloff", value.highlightRolloff)
            .put("halation", value.halation)
            .put("bloom", value.bloom)
            .put("shadowLift", value.shadowLift)
            .put("toe", value.toe)
            .put("shoulder", value.shoulder)
            .put("highlightSaturation", value.highlightSaturation)
            .put("shadowSaturation", value.shadowSaturation)
            .put("highlightWarmth", value.highlightWarmth)
            .put("shadowCoolness", value.shadowCoolness)
    }

    private fun adjustmentsFromJson(raw: String?): FilmAdjustments? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            FilmAdjustments(
                lutIntensity = json.optInt("lutIntensity", defaultAdjustments.lutIntensity),
                grain = json.optInt("grain", defaultAdjustments.grain),
                dispersion = json.optInt("dispersion", defaultAdjustments.dispersion),
                vignette = json.optInt("vignette", defaultAdjustments.vignette),
                sharpen = json.optInt("sharpen", defaultAdjustments.sharpen),
                clarity = json.optInt("clarity", defaultAdjustments.clarity),
                highlightRolloff = json.optInt("highlightRolloff", defaultAdjustments.highlightRolloff),
                halation = json.optInt("halation", defaultAdjustments.halation),
                bloom = json.optInt("bloom", defaultAdjustments.bloom),
                shadowLift = json.optInt("shadowLift", defaultAdjustments.shadowLift),
                toe = json.optInt("toe", defaultAdjustments.toe),
                shoulder = json.optInt("shoulder", defaultAdjustments.shoulder),
                highlightSaturation = json.optInt("highlightSaturation", defaultAdjustments.highlightSaturation),
                shadowSaturation = json.optInt("shadowSaturation", defaultAdjustments.shadowSaturation),
                highlightWarmth = json.optInt("highlightWarmth", defaultAdjustments.highlightWarmth),
                shadowCoolness = json.optInt("shadowCoolness", defaultAdjustments.shadowCoolness),
            )
        }.getOrNull()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private data class ControlSpec(
        val key: String,
        val titleRes: Int,
        val summaryRes: Int,
        val min: Int,
        val max: Int,
        val getter: (FilmAdjustments) -> Int,
        val updater: (FilmAdjustments, Int) -> FilmAdjustments,
    )

    private data class ControlGroupSpec(
        val titleRes: Int,
        val summaryRes: Int,
        val keys: List<String>,
        val expandedByDefault: Boolean,
    )

    private enum class Tab {
        EDIT,
        BATCH,
    }

    private enum class EditorPanel {
        LUT,
        FILM,
    }

    companion object {
        private const val CATEGORY_ALL = "热门"
        private const val SESSION_PREFS = "luma_film_session"
        private const val KEY_PHOTO_URIS = "photo_uris"
        private const val KEY_ACTIVE_PHOTO_INDEX = "active_photo_index"
        private const val KEY_BATCH_PHOTO_INDICES = "batch_photo_indices"
        private const val KEY_SELECTED_LUT_ID = "selected_lut_id"
        private const val KEY_BATCH_LUT_IDS = "batch_lut_ids"
        private const val KEY_ADJUSTMENTS = "adjustments"
    }
}
