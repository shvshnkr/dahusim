package fr.husi.ui

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

@Serializable
sealed class NavRoutes : NavKey {

    companion object {
        val savedStateConfiguration
            get() = SavedStateConfiguration {
                serializersModule = SerializersModule {
                    polymorphic(NavKey::class) {
                        subclass(Configuration::class, Configuration.serializer())
                        subclass(Simple::class, Simple.serializer())
                        subclass(Groups::class, Groups.serializer())
                        subclass(Library::class, Library.serializer())
                        subclass(More::class, More.serializer())
                        subclass(LibraryGroup::class, LibraryGroup.serializer())
                        subclass(Route::class, Route.serializer())
                        subclass(Settings::class, Settings.serializer())
                        subclass(Plugin::class, Plugin.serializer())
                        subclass(Log::class, Log.serializer())
                        subclass(Dashboard::class, Dashboard.serializer())
                        subclass(Tools::class, Tools.serializer())
                        subclass(ToolsPage.Stun::class, ToolsPage.Stun.serializer())
                        subclass(ToolsPage.GetCert::class, ToolsPage.GetCert.serializer())
                        subclass(ToolsPage.VPNScanner::class, ToolsPage.VPNScanner.serializer())
                        subclass(ToolsPage.SpeedTest::class, ToolsPage.SpeedTest.serializer())
                        subclass(ToolsPage.RuleSetMatch::class, ToolsPage.RuleSetMatch.serializer())
                        subclass(About::class, About.serializer())
                        subclass(AppUpdate::class, AppUpdate.serializer())
                        subclass(QuickSettings::class, QuickSettings.serializer())
                        subclass(DahusimHub::class, DahusimHub.serializer())
                        subclass(DahusimNetwork::class, DahusimNetwork.serializer())
                        subclass(DahusimSubscriptions::class, DahusimSubscriptions.serializer())
                        subclass(DahusimAutoselect::class, DahusimAutoselect.serializer())
                        subclass(DahusimDiagnostics::class, DahusimDiagnostics.serializer())
                        subclass(Libraries::class, Libraries.serializer())
                        subclass(ProfileEditor::class, ProfileEditor.serializer())
                        subclass(ConnectionsDetail::class, ConnectionsDetail.serializer())
                        subclass(AppManager::class, AppManager.serializer())
                        subclass(Assets::class, Assets.serializer())
                        subclass(AppList::class, AppList.serializer())
                        subclass(ConfigEditor::class, ConfigEditor.serializer())
                        subclass(AssetEdit::class, AssetEdit.serializer())
                        subclass(GroupSettings::class, GroupSettings.serializer())
                        subclass(RouteSettings::class, RouteSettings.serializer())
                    }
                }
            }
    }

    @Serializable
    data object Configuration : NavRoutes()

    @Serializable
    data object Simple : NavRoutes()

    /** @deprecated Use [Library]; kept for saved-state compatibility. */
    @Serializable
    data object Groups : NavRoutes()

    @Serializable
    data object Library : NavRoutes()

    @Serializable
    data object More : NavRoutes()

    @Serializable
    data class LibraryGroup(
        val groupId: Long,
    ) : NavRoutes()

    @Serializable
    data object Route : NavRoutes()

    @Serializable
    data object Settings : NavRoutes()

    @Serializable
    data object Plugin : NavRoutes()

    @Serializable
    data object Log : NavRoutes()

    @Serializable
    data object Dashboard : NavRoutes()

    @Serializable
    data object Tools : NavRoutes()

    @Serializable
    sealed class ToolsPage : NavRoutes() {
        @Serializable
        data object Stun : ToolsPage()

        @Serializable
        data object GetCert : ToolsPage()

        @Serializable
        data object VPNScanner : ToolsPage()

        @Serializable
        data object SpeedTest : ToolsPage()

        @Serializable
        data object RuleSetMatch : ToolsPage()

    }

    @Serializable
    data object About : NavRoutes()

    @Serializable
    data object AppUpdate : NavRoutes()

    @Serializable
    data object QuickSettings : NavRoutes()

    @Serializable
    data object DahusimHub : NavRoutes()

    @Serializable
    data object DahusimNetwork : NavRoutes()

    @Serializable
    data object DahusimSubscriptions : NavRoutes()

    @Serializable
    data object DahusimAutoselect : NavRoutes()

    @Serializable
    data object DahusimDiagnostics : NavRoutes()

    @Serializable
    data object Libraries : NavRoutes()

    @Serializable
    data class ProfileEditor(
        val type: Int,
        val id: Long = -1L,
        val subscription: Boolean = false,
        val resultKey: String = "${type}-${id}-${subscription}",
    ) : NavRoutes()

    @Serializable
    data class ConnectionsDetail(
        val uuid: String,
    ) : NavRoutes()

    @Serializable
    data object AppManager : NavRoutes()

    @Serializable
    data object Assets : NavRoutes()

    @Serializable
    data class AppList(
        val initialPackages: Set<String> = emptySet(),
        val resultKey: String = initialPackages.hashCode().toString(),
    ) : NavRoutes()

    @Serializable
    data class ConfigEditor(
        val initialText: String = "",
        val resultKey: String,
    ) : NavRoutes()

    @Serializable
    data class AssetEdit(
        val assetName: String = "",
        val resultKey: String = assetName,
    ) : NavRoutes()

    @Serializable
    data class GroupSettings(
        val groupId: Long = 0L,
    ) : NavRoutes()

    @Serializable
    data class RouteSettings(
        val routeId: Long = -1L,
        val useDraft: Boolean = false,
        val initialState: RouteSettingsUiState? = null,
    ) : NavRoutes()

}
