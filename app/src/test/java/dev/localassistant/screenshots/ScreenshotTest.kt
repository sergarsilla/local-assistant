package dev.localassistant.screenshots

import android.content.Intent
import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import dev.localassistant.history.ConversationSummary
import dev.localassistant.llm.ChatError
import dev.localassistant.llm.EngineState
import dev.localassistant.llm.Role
import dev.localassistant.llm.UiMessage
import dev.localassistant.models.Catalog
import dev.localassistant.models.ModelFormat
import dev.localassistant.models.TransferState
import dev.localassistant.system.SystemStatus
import dev.localassistant.tools.ActionKind
import dev.localassistant.tools.PendingAction
import dev.localassistant.tools.ToolActivity
import dev.localassistant.ui.history.ConversationDetail
import dev.localassistant.ui.history.HistoryActions
import dev.localassistant.ui.history.HistoryGrouping
import dev.localassistant.ui.history.HistoryScreen
import dev.localassistant.ui.history.HistoryUiState
import dev.localassistant.ui.home.HomeActions
import dev.localassistant.ui.home.HomeScreen
import dev.localassistant.ui.home.HomeUiState
import dev.localassistant.ui.home.SetupStep
import dev.localassistant.ui.home.StepId
import dev.localassistant.ui.home.StepState
import dev.localassistant.ui.keyboard.VoiceKeyboard
import dev.localassistant.ui.keyboard.VoiceKeyboardActions
import dev.localassistant.ui.models.CatalogItemUi
import dev.localassistant.ui.models.InstalledModelUi
import dev.localassistant.ui.models.ModelsActions
import dev.localassistant.ui.models.ModelsScreen
import dev.localassistant.ui.models.ModelsUiState
import dev.localassistant.ui.panel.PanelActions
import dev.localassistant.ui.panel.PanelScreen
import dev.localassistant.ui.panel.PanelUiState
import dev.localassistant.ui.panel.Suggestion
import dev.localassistant.ui.settings.SettingsActions
import dev.localassistant.ui.settings.SettingsScreen
import dev.localassistant.ui.settings.SettingsUiState
import dev.localassistant.ui.theme.AssistantTheme
import dev.localassistant.voice.DictationState
import dev.localassistant.voice.VoiceState
import java.lang.reflect.Proxy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every screen in its important states. Run with `-Proborazzi.test.record=true`; the
 * images land in app/build/outputs/roborazzi and CI publishes them, so the UI can be reviewed
 * without a phone and regressions show up as image diffs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "es-w411dp-h914dp-xxhdpi")
class ScreenshotTest {

    @Test fun panelEmpty() = capture("panel_empty") { PanelScreen(panel, noop<PanelActions>()) }

    @Test fun panelConversation() = capture("panel_conversation") {
        PanelScreen(
            panel.copy(
                messages = listOf(
                    UiMessage(Role.USER, "¿Qué es un agujero negro? Explícamelo corto"),
                    UiMessage(
                        Role.ASSISTANT,
                        "Un **agujero negro** es una región del espacio donde la gravedad es tan intensa que " +
                            "nada escapa, ni siquiera la luz.\n\n- Se forma al colapsar una estrella muy masiva.\n" +
                            "- Su borde se llama *horizonte de sucesos*.",
                    ),
                    UiMessage(Role.USER, "Pon un temporizador de 10 minutos"),
                    UiMessage(Role.ASSISTANT, "", streaming = true),
                ),
                generating = true,
                toolActivity = ToolActivity("set_timer", "10 min"),
            ),
            noop<PanelActions>(),
        )
    }

    @Test fun panelListening() = capture("panel_listening") {
        PanelScreen(panel.copy(voice = VoiceState.Listening(level = 0.06f, hearingSpeech = true), screenAvailable = true), noop<PanelActions>())
    }

    @Test fun panelAction() = capture("panel_action") {
        PanelScreen(
            panel.copy(
                messages = listOf(
                    UiMessage(Role.USER, "Escribe a Ana que llego en diez minutos"),
                    UiMessage(Role.ASSISTANT, "Te he preparado el mensaje para Ana. ¿Lo envío?"),
                ),
                pendingAction = PendingAction(ActionKind.MESSAGE, "Ana García: Llego en diez minutos", Intent()),
            ),
            noop<PanelActions>(),
        )
    }

    @Test fun panelNoModel() = capture("panel_no_model") {
        PanelScreen(panel.copy(engine = EngineState.NoModel, error = ChatError.NoModel, voice = VoiceState.NeedsModel), noop<PanelActions>())
    }

    @Config(qualifiers = "+night")
    @Test fun panelConversationDark() = panelConversation()

    @Test fun homeSetup() = capture("home_setup") {
        HomeScreen(
            HomeUiState(
                required = listOf(
                    SetupStep(StepId.ASSISTANT, StepState.Done),
                    SetupStep(StepId.MICROPHONE, StepState.Done),
                    SetupStep(StepId.SPEECH_MODEL, StepState.Downloading(420_000_000, 671_000_000), 671_000_000),
                    SetupStep(StepId.LANGUAGE_MODEL, StepState.Todo, 2_588_147_712),
                ),
                optional = listOf(
                    SetupStep(StepId.VOICE, StepState.Todo, 81_000_000),
                    SetupStep(StepId.SCREEN, StepState.Done),
                    SetupStep(StepId.KEYBOARD, StepState.Todo),
                ),
            ),
            noop<HomeActions>(),
        )
    }

    @Test fun homeReady() = capture("home_ready") {
        HomeScreen(
            HomeUiState(
                required = StepId.entries.take(4).map { SetupStep(it, StepState.Done) },
                optional = StepId.entries.drop(4).map { SetupStep(it, StepState.Done) },
                activeModel = "gemma-4-E2B-it.litertlm",
            ),
            noop<HomeActions>(),
        )
    }

    @Config(qualifiers = "+night")
    @Test fun homeReadyDark() = homeReady()

    @Test fun models() = capture("models") {
        ModelsScreen(
            ModelsUiState(
                installed = listOf(
                    InstalledModelUi("gemma-4-E2B-it.litertlm", 2_588_147_712, ModelFormat.LITERT_LM, 51.8, active = true, runningOn = "GPU"),
                    InstalledModelUi("Qwen3-4B-Instruct-2507-Q4_K_M.gguf", 2_500_000_000, ModelFormat.GGUF, 9.4, active = false, runningOn = null),
                ),
                transfers = listOf(TransferState.Running("gemma-4-E4B-it.litertlm", 1_200_000_000, 3_660_000_000)),
                catalog = Catalog.recommended.mapIndexed { index, entry ->
                    CatalogItemUi(entry, installed = index == 0, transfer = null, fits = index != 1)
                },
                freeBytes = 23_400_000_000,
            ),
            noop<ModelsActions>(),
        )
    }

    @Test fun history() = capture("history") {
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        HistoryScreen(
            HistoryUiState(
                groups = HistoryGrouping.group(
                    listOf(
                        ConversationSummary(1, now - hour, "Receta de paella para cuatro", 6),
                        ConversationSummary(2, now - 3 * hour, "Pon una alarma a las 7:30", 2),
                        ConversationSummary(3, now - 26 * hour, "Resume este artículo sobre baterías", 4),
                        ConversationSummary(4, now - 5 * 24 * hour, "¿Cómo se dice 'ventanilla' en inglés?", 2),
                    ),
                    now,
                ),
                hasConversations = true,
            ),
            noop<HistoryActions>(),
        )
    }

    @Test fun historyDetail() = capture("history_detail") {
        HistoryScreen(
            HistoryUiState(
                hasConversations = true,
                detail = ConversationDetail(
                    ConversationSummary(1, System.currentTimeMillis(), "Receta de paella para cuatro", 2),
                    listOf(
                        UiMessage(Role.USER, "Receta de paella para cuatro"),
                        UiMessage(
                            Role.ASSISTANT,
                            "### Ingredientes\n1. 400 g de arroz bomba\n2. 1 l de caldo\n3. Azafrán\n\n" +
                                "Sofríe, añade el arroz y **no lo remuevas** después de echar el caldo.",
                        ),
                    ),
                ),
            ),
            noop<HistoryActions>(),
        )
    }

    @Test fun settings() = capture("settings") {
        SettingsScreen(
            SettingsUiState(status = SystemStatus(isAssistant = true, micGranted = true, screenTextEnabled = true)),
            noop<SettingsActions>(),
        )
    }

    @Test fun keyboard() = capture("keyboard") {
        VoiceKeyboard(DictationState.Listening(0.05f, hearingSpeech = true), noop<VoiceKeyboardActions>())
    }

    private val panel = PanelUiState(
        engine = EngineState.Ready("gemma-4-E2B-it.litertlm", 1_800, "GPU"),
        suggestions = listOf(
            Suggestion("Temporizador de 10 min", ""),
            Suggestion("Alarma a las 7:30", ""),
            Suggestion("Resume la pantalla", ""),
            Suggestion("Sorpréndeme", ""),
        ),
    )

    private fun capture(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/outputs/roborazzi/$name${if (isNight()) "_dark" else ""}.png") {
            AssistantTheme { content() }
        }
    }

    private fun isNight(): Boolean =
        (org.robolectric.RuntimeEnvironment.getApplication().resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** An implementation of an actions interface that ignores every call. */
    private inline fun <reified T> noop(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            // Compose compares parameters, so the Object methods must behave.
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "noop ${T::class.java.simpleName}"
                else -> null
            }
        } as T
}
