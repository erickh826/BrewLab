package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.domain.model.Recipe
import com.example.domain.model.RecipeStep
import com.example.domain.model.RecipeValidationResult
import com.example.domain.model.StepType
import com.example.ui.components.BrewFormat
import com.example.ui.components.CumulativeWeightChartCard
import com.example.ui.components.InstrumentSectionCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.MonoReadoutSmall
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalCrimsonBg
import com.example.ui.theme.SignalOchre
import com.example.ui.theme.SignalOchreBg
import com.example.ui.theme.SignalTeal
import com.example.ui.theme.SignalTealBg
import java.util.UUID

@Composable
fun RecipesScreen(
  recipes: List<Recipe>,
  selectedRecipeId: String?,
  isRecording: Boolean,
  onSelectForBrew: (Recipe) -> Unit,
  onCreateRecipe: () -> Unit,
  onEditRecipe: (Recipe) -> Unit,
  onDuplicateRecipe: (Recipe) -> Unit,
  onDeleteRecipe: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var pendingDeleteRecipe by remember { mutableStateOf<Recipe?>(null) }

  pendingDeleteRecipe?.let { target ->
    AlertDialog(
      onDismissRequest = { pendingDeleteRecipe = null },
      title = { Text("刪除配方？") },
      text = {
        Text("確定要刪除「${target.name}」嗎？既有歷史沖煮紀錄會保留當時的配方快照，不受刪除影響。")
      },
      confirmButton = {
        Button(
          onClick = {
            onDeleteRecipe(target.id)
            pendingDeleteRecipe = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = SignalCrimson),
          modifier = Modifier.testTag("recipe_delete_confirm_button"),
        ) {
          Text("確定刪除")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingDeleteRecipe = null }) { Text("取消") }
      },
    )
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = 680.dp),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 92.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (recipes.isEmpty()) {
        item {
          InstrumentSectionCard {
            Text("尚未建立任何配方", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = "請點擊右下角「新增配方」按鈕建立你的手沖注水曲線配方。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        items(recipes, key = { it.id }) { recipe ->
          val isSelected = recipe.id == selectedRecipeId
          InstrumentSectionCard(
            modifier = Modifier.testTag("recipe_card_${recipe.id}"),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.Top,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "${recipe.name}（v${recipe.version}）",
                  style = MaterialTheme.typography.titleLarge,
                  fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = recipe.equipment,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              if (isSelected) {
                StatusPill(
                  text = "使用中",
                  containerColor = SignalTealBg,
                  contentColor = SignalTeal,
                )
              }
            }

            if (recipe.isDemoTemplate) {
              Spacer(modifier = Modifier.height(6.dp))
              StatusPill(
                text = "示範配方（可編輯範例，非業界標準）",
                containerColor = SignalOchreBg,
                contentColor = SignalOchre,
              )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = "粉量 ${BrewFormat.formatGrams(recipe.coffeeDoseG, 0)} · 目標總水量 ${BrewFormat.formatGrams(recipe.totalTargetWaterG, 0)} · 時長 ${BrewFormat.formatSecAsMmSs(recipe.totalDurationSec)} · ${recipe.steps.size} 步驟",
              style = MonoReadoutSmall,
            )

            if (recipe.beanNotes.isNotBlank() || recipe.roastNotes.isNotBlank() || recipe.grinderNotes.isNotBlank()) {
              Spacer(modifier = Modifier.height(6.dp))
              val notesLine = listOfNotNull(
                recipe.beanNotes.takeIf { it.isNotBlank() }?.let { "豆種：$it" },
                recipe.roastNotes.takeIf { it.isNotBlank() }?.let { "焙度：$it" },
                recipe.grinderNotes.takeIf { it.isNotBlank() }?.let { "研磨：$it" },
              ).joinToString(" · ")
              Text(
                text = notesLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              FilledTonalButton(
                onClick = { onSelectForBrew(recipe) },
                enabled = !isRecording && !isSelected,
                modifier = Modifier
                  .height(48.dp)
                  .testTag("recipe_select_button_${recipe.id}"),
              ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isSelected) "已選用" else "選用")
              }
              OutlinedButton(
                onClick = { onEditRecipe(recipe) },
                modifier = Modifier
                  .height(48.dp)
                  .testTag("recipe_edit_button_${recipe.id}"),
              ) {
                Icon(Icons.Filled.Edit, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("編輯")
              }
              IconButton(
                onClick = { onDuplicateRecipe(recipe) },
                modifier = Modifier.testTag("recipe_duplicate_button_${recipe.id}"),
              ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "複製配方")
              }
              IconButton(
                onClick = { pendingDeleteRecipe = recipe },
                modifier = Modifier.testTag("recipe_delete_button_${recipe.id}"),
              ) {
                Icon(
                  Icons.Filled.Delete,
                  contentDescription = "刪除配方",
                  tint = MaterialTheme.colorScheme.error,
                )
              }
            }
          }
        }
      }
    }

    FloatingActionButton(
      onClick = onCreateRecipe,
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(16.dp)
        .testTag("recipes_create_fab"),
      containerColor = MaterialTheme.colorScheme.primary,
      contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
      Icon(Icons.Filled.Add, contentDescription = "新增配方")
    }
  }
}

private data class StepDraft(
  val id: String,
  val type: StepType,
  val label: String,
  val startSecText: String,
  val durationSecText: String,
  val addedWaterText: String,
  val waterTolText: String,
  val rateTolText: String,
) {
  fun toDomain(): RecipeStep = RecipeStep(
    id = id,
    type = type,
    label = label.trim(),
    startSec = startSecText.toIntOrNull() ?: -1,
    durationSec = durationSecText.toIntOrNull() ?: 0,
    addedWaterG = if (type == StepType.REST) 0.0 else (addedWaterText.toDoubleOrNull() ?: -1.0),
    waterToleranceG = waterTolText.toDoubleOrNull() ?: -1.0,
    rateToleranceGPerSec = rateTolText.toDoubleOrNull() ?: -1.0,
  )

  companion object {
    fun fromDomain(step: RecipeStep): StepDraft = StepDraft(
      id = step.id,
      type = step.type,
      label = step.label,
      startSecText = step.startSec.toString(),
      durationSecText = step.durationSec.toString(),
      addedWaterText = if (step.type == StepType.REST) "0" else BrewFormat.formatGramsNumber(step.addedWaterG, 1),
      waterTolText = BrewFormat.formatGramsNumber(step.waterToleranceG, 1),
      rateTolText = BrewFormat.formatRateNumber(step.rateToleranceGPerSec),
    )
  }
}

@Composable
fun RecipeEditorScreen(
  initialRecipe: Recipe?,
  validateRecipe: (Recipe) -> RecipeValidationResult,
  onSave: (Recipe, Boolean) -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isNew = initialRecipe == null
  var name by remember { mutableStateOf(initialRecipe?.name ?: "") }
  var equipment by remember { mutableStateOf(initialRecipe?.equipment ?: "Hario V60 02 濾杯") }
  var doseText by remember { mutableStateOf(initialRecipe?.coffeeDoseG?.let { "%.1f".format(it) } ?: "20.0") }
  var totalWaterText by remember { mutableStateOf(initialRecipe?.totalTargetWaterG?.let { "%.1f".format(it) } ?: "300.0") }
  var beanNotes by remember { mutableStateOf(initialRecipe?.beanNotes ?: "") }
  var roastNotes by remember { mutableStateOf(initialRecipe?.roastNotes ?: "") }
  var grinderNotes by remember { mutableStateOf(initialRecipe?.grinderNotes ?: "") }

  val stepDrafts = remember {
    mutableStateListOf<StepDraft>().apply {
      val sourceSteps = initialRecipe?.steps ?: listOf(
        RecipeStep(
          id = UUID.randomUUID().toString(),
          type = StepType.POUR,
          label = "第一段注水",
          startSec = 0,
          durationSec = 20,
          addedWaterG = 60.0,
        ),
        RecipeStep(
          id = UUID.randomUUID().toString(),
          type = StepType.REST,
          label = "悶蒸靜置",
          startSec = 20,
          durationSec = 25,
          addedWaterG = 0.0,
        ),
      )
      addAll(sourceSteps.map { StepDraft.fromDomain(it) })
    }
  }

  val candidateRecipe = Recipe(
    id = initialRecipe?.id ?: UUID.randomUUID().toString(),
    name = name.trim(),
    version = initialRecipe?.version ?: 1,
    equipment = equipment.trim(),
    coffeeDoseG = doseText.toDoubleOrNull() ?: 0.0,
    totalTargetWaterG = totalWaterText.toDoubleOrNull() ?: 0.0,
    beanNotes = beanNotes.trim(),
    roastNotes = roastNotes.trim(),
    grinderNotes = grinderNotes.trim(),
    isDemoTemplate = initialRecipe?.isDemoTemplate ?: false,
    steps = stepDrafts.map { it.toDomain() },
  )
  val validation = validateRecipe(candidateRecipe)

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .widthIn(max = 680.dp),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        InstrumentSectionCard {
          Text(
            text = if (isNew) "新增配方" else "編輯配方（儲存將升級為 v${candidateRecipe.version + 1}）",
            style = MaterialTheme.typography.headlineSmall,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "更新配方只會影響之後的新沖煮，不會改寫既有的歷史紀錄快照。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(12.dp))
          OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("配方名稱") },
            modifier = Modifier
              .fillMaxWidth()
              .testTag("recipe_editor_name_input"),
          )
          Spacer(modifier = Modifier.height(8.dp))
          OutlinedTextField(
            value = equipment,
            onValueChange = { equipment = it },
            label = { Text("沖煮器具") },
            modifier = Modifier
              .fillMaxWidth()
              .testTag("recipe_editor_equipment_input"),
          )
          Spacer(modifier = Modifier.height(8.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
              value = doseText,
              onValueChange = { doseText = it },
              label = { Text("咖啡粉量 (g)") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              modifier = Modifier
                .weight(1f)
                .testTag("recipe_editor_dose_input"),
            )
            OutlinedTextField(
              value = totalWaterText,
              onValueChange = { totalWaterText = it },
              label = { Text("目標總水量 (g)") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              modifier = Modifier
                .weight(1f)
                .testTag("recipe_editor_water_input"),
            )
          }
          Spacer(modifier = Modifier.height(8.dp))
          OutlinedTextField(
            value = beanNotes,
            onValueChange = { beanNotes = it },
            label = { Text("豆種備註（選填）") },
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(modifier = Modifier.height(8.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
              value = roastNotes,
              onValueChange = { roastNotes = it },
              label = { Text("焙度（選填）") },
              modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
              value = grinderNotes,
              onValueChange = { grinderNotes = it },
              label = { Text("磨豆機/刻度（選填）") },
              modifier = Modifier.weight(1f),
            )
          }
        }
      }

      // Live target curve preview
      item {
        CumulativeWeightChartCard(
          title = "目標累計注水曲線預覽",
          steps = candidateRecipe.steps.filter { it.startSec >= 0 && it.durationSec > 0 },
          targetTotalWaterG = candidateRecipe.totalTargetWaterG.coerceAtLeast(100.0),
          actualSegments = emptyList(),
          events = emptyList(),
          chartHeight = 150.dp,
        )
      }

      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("注水與靜置步驟", style = MaterialTheme.typography.titleLarge)
          OutlinedButton(
            onClick = {
              val prevEnd = stepDrafts.lastOrNull()?.let {
                (it.startSecText.toIntOrNull() ?: 0) + (it.durationSecText.toIntOrNull() ?: 0)
              } ?: 0
              stepDrafts += StepDraft(
                id = UUID.randomUUID().toString(),
                type = StepType.POUR,
                label = "第 ${stepDrafts.size + 1} 步",
                startSecText = prevEnd.toString(),
                durationSecText = "30",
                addedWaterText = "60",
                waterTolText = "5.0",
                rateTolText = "1.5",
              )
            },
            modifier = Modifier
              .height(48.dp)
              .testTag("recipe_editor_add_step_button"),
          ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("新增步驟")
          }
        }
      }

      itemsIndexed(stepDrafts, key = { _, item -> item.id }) { index, draft ->
        InstrumentSectionCard {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("步驟 ${index + 1}", style = MaterialTheme.typography.titleMedium)
            if (stepDrafts.size > 1) {
              IconButton(
                onClick = { stepDrafts.removeAt(index) },
                modifier = Modifier.testTag("recipe_editor_remove_step_$index"),
              ) {
                Icon(Icons.Filled.Delete, contentDescription = "移除步驟", tint = SignalCrimson)
              }
            }
          }

          SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            StepType.entries.forEachIndexed { segIdx, stepType ->
              SegmentedButton(
                selected = draft.type == stepType,
                onClick = {
                  stepDrafts[index] = draft.copy(
                    type = stepType,
                    addedWaterText = if (stepType == StepType.REST) "0" else if (draft.addedWaterText == "0") "60" else draft.addedWaterText,
                  )
                },
                shape = SegmentedButtonDefaults.itemShape(index = segIdx, count = StepType.entries.size),
              ) {
                Text(stepType.displayName)
              }
            }
          }

          Spacer(modifier = Modifier.height(8.dp))
          OutlinedTextField(
            value = draft.label,
            onValueChange = { stepDrafts[index] = draft.copy(label = it) },
            label = { Text("步驟名稱") },
            modifier = Modifier.fillMaxWidth(),
          )
          Spacer(modifier = Modifier.height(8.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = draft.startSecText,
              onValueChange = { stepDrafts[index] = draft.copy(startSecText = it) },
              label = { Text("開始 (秒)") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
              value = draft.durationSecText,
              onValueChange = { stepDrafts[index] = draft.copy(durationSecText = it) },
              label = { Text("持續 (秒)") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
              value = draft.addedWaterText,
              onValueChange = { stepDrafts[index] = draft.copy(addedWaterText = it) },
              enabled = draft.type == StepType.POUR,
              label = { Text("水量 (g)") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              modifier = Modifier.weight(1f),
            )
          }
          Spacer(modifier = Modifier.height(8.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = draft.waterTolText,
              onValueChange = { stepDrafts[index] = draft.copy(waterTolText = it) },
              label = { Text("水量誤差容許 (±g)") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
              value = draft.rateTolText,
              onValueChange = { stepDrafts[index] = draft.copy(rateTolText = it) },
              label = { Text("速率誤差容許 (±g/s)") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              modifier = Modifier.weight(1f),
            )
          }
        }
      }

      if (!validation.isValid) {
        item {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(MaterialTheme.shapes.small)
              .background(SignalCrimsonBg)
              .border(1.dp, SignalCrimson.copy(alpha = 0.4f), MaterialTheme.shapes.small)
              .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text("配方驗證未通過：", style = MaterialTheme.typography.titleMedium, color = SignalCrimson)
            validation.errors.forEach { err ->
              Text("• $err", style = MaterialTheme.typography.bodyMedium, color = SignalCrimson)
            }
          }
        }
      }

      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
              .weight(1f)
              .height(50.dp)
              .testTag("recipe_editor_cancel_button"),
          ) {
            Text("取消")
          }
          Button(
            onClick = { onSave(candidateRecipe, isNew) },
            enabled = validation.isValid,
            modifier = Modifier
              .weight(2f)
              .height(50.dp)
              .testTag("recipe_editor_save_button"),
          ) {
            Text("儲存配方")
          }
        }
        Spacer(modifier = Modifier.height(24.dp))
      }
    }
  }
}
