; Husi Windows Installer — NSIS script
; Placeholders are replaced by package.sh before compilation.

Unicode true

!include "MUI2.nsh"
!include "FileFunc.nsh"
!include "nsDialogs.nsh"
!include "WinMessages.nsh"

; --- Metadata ---
!define PACKAGE_NAME    "__HUSI_PACKAGE_NAME__"
!define APP_NAME        "__HUSI_APP_NAME__"
!define APP_NAME_ZH_CN  "__HUSI_APP_NAME_ZH_CN__"
!define APP_VERSION     "__HUSI_APP_VERSION__"
!define APP_DESCRIPTION "__HUSI_APP_DESCRIPTION__"
!define APP_URL         "__HUSI_APP_URL__"
!define MAINTAINER      "__HUSI_MAINTAINER__"

Name "${APP_NAME} ${APP_VERSION}"
OutFile "__HUSI_OUTPUT_FILE__"
InstallDir "$LOCALAPPDATA\Programs\${APP_NAME}"
InstallDirRegKey HKCU "Software\${PACKAGE_NAME}\Installer" "InstallDir"
RequestExecutionLevel user

; --- Version info embedded in exe ---
VIProductVersion "__HUSI_VI_VERSION__"
VIAddVersionKey "ProductName" "${APP_NAME}"
VIAddVersionKey "ProductVersion" "${APP_VERSION}"
VIAddVersionKey "FileVersion" "__HUSI_VI_VERSION__"
VIAddVersionKey "CompanyName" "${MAINTAINER}"
VIAddVersionKey "FileDescription" "${APP_DESCRIPTION}"
VIAddVersionKey "LegalCopyright" "${MAINTAINER}"

; --- MUI settings ---
!define MUI_ABORTWARNING

Var CreateDesktopShortcut
Var CreateStartMenuShortcut
Var CheckboxDesktopShortcut
Var CheckboxStartMenuShortcut
Var InstallTemurin21
Var CheckboxInstallJava
Var HasInstalledJava

; --- Pages ---
!insertmacro MUI_PAGE_LICENSE "__HUSI_LICENSE_FILE__"
Page custom win64ReqPageCreate win64ReqPageLeave
Page custom javaRuntimePageCreate javaRuntimePageLeave
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
Page custom shortcutsPageCreate shortcutsPageLeave
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "SimpChinese"

LangString ShortcutPageTitle ${LANG_ENGLISH} "Shortcuts"
LangString ShortcutPageTitle ${LANG_SIMPCHINESE} "快捷方式"
LangString ShortcutAppName ${LANG_ENGLISH} "${APP_NAME}"
LangString ShortcutAppName ${LANG_SIMPCHINESE} "${APP_NAME_ZH_CN}"
LangString ShortcutPageSubtitle ${LANG_ENGLISH} "Choose which shortcuts to create."
LangString ShortcutPageSubtitle ${LANG_SIMPCHINESE} "选择要创建的快捷方式。"
LangString ShortcutPageDescription ${LANG_ENGLISH} "Select the shortcuts to create for ${APP_NAME}."
LangString ShortcutPageDescription ${LANG_SIMPCHINESE} "选择要为 ${APP_NAME} 创建的快捷方式。"
LangString DesktopShortcutLabel ${LANG_ENGLISH} "Create a desktop shortcut"
LangString DesktopShortcutLabel ${LANG_SIMPCHINESE} "创建桌面快捷方式"
LangString StartMenuShortcutLabel ${LANG_ENGLISH} "Create a Start Menu shortcut"
LangString StartMenuShortcutLabel ${LANG_SIMPCHINESE} "创建开始菜单快捷方式"
LangString InstallSectionName ${LANG_ENGLISH} "Install"
LangString InstallSectionName ${LANG_SIMPCHINESE} "安装"
LangString UninstallSectionName ${LANG_ENGLISH} "Uninstall"
LangString UninstallSectionName ${LANG_SIMPCHINESE} "卸载"
LangString UninstallShortcutName ${LANG_ENGLISH} "Uninstall"
LangString UninstallShortcutName ${LANG_SIMPCHINESE} "卸载"
LangString JavaRuntimePageTitle ${LANG_ENGLISH} "Java runtime"
LangString JavaRuntimePageTitle ${LANG_SIMPCHINESE} "Java 运行环境"
LangString JavaRuntimePageSubtitle ${LANG_ENGLISH} "Optional: install a Java runtime if it is not installed yet."
LangString JavaRuntimePageSubtitle ${LANG_SIMPCHINESE} "可选：若尚未安装 Java 运行环境，可在此安装。"
LangString JavaRuntimePageDescription ${LANG_ENGLISH} "If Java is already installed, you can keep using it. If not, you may tick the box below to install a recommended Java 21 runtime (silent MSI, /qn). This does not override your existing JAVA_HOME unless you explicitly choose this option."
LangString JavaRuntimePageDescription ${LANG_SIMPCHINESE} "若系统已安装 Java，可继续直接使用。若未安装，可勾选下方选项安装推荐的 Java 21 运行环境（静默 MSI /qn）。除非您主动选择，此操作不会覆盖现有 JAVA_HOME。"
LangString InstallTemurin21Label ${LANG_ENGLISH} "Download and install a recommended Java 21 runtime (optional, explicit opt-in; silent MSI /qn; network + UAC may apply)"
LangString InstallTemurin21Label ${LANG_SIMPCHINESE} "下载并安装推荐的 Java 21 运行环境（可选，需手动勾选；静默 MSI /qn；联网且可能 UAC）"
LangString Win64ReqTitle ${LANG_ENGLISH} "System requirements"
LangString Win64ReqTitle ${LANG_SIMPCHINESE} "系统要求"
LangString Win64ReqText ${LANG_ENGLISH} "${APP_NAME} requires 64-bit Windows (x64).$\r$\n32-bit Windows is not supported.$\r$\n$\r$\nARM64 Windows builds are not offered in releases yet — use the x64 installer on Intel/AMD PCs."
LangString Win64ReqText ${LANG_SIMPCHINESE} "${APP_NAME} 需要 64 位 Windows (x64)。$\r$\n不支持 32 位 Windows。$\r$\n$\r$\n暂未提供 ARM64 Windows 安装包 — 请在 Intel/AMD 电脑上使用 x64 安装程序。"

Function .onInit
    StrCpy $CreateDesktopShortcut ${BST_CHECKED}
    StrCpy $CreateStartMenuShortcut ${BST_CHECKED}
    StrCpy $InstallTemurin21 ${BST_UNCHECKED}
    StrCpy $HasInstalledJava 0
    Call detectInstalledJava
    IfSilent init_done
    StrCmp $HasInstalledJava 1 init_done
    StrCpy $InstallTemurin21 ${BST_CHECKED}
init_done:
FunctionEnd

Function win64ReqPageCreate
    !insertmacro MUI_HEADER_TEXT "$(Win64ReqTitle)" "$(Win64ReqTitle)"
    nsDialogs::Create 1018
    Pop $0
    StrCmp $0 error 0 +2
    Abort
    ${NSD_CreateLabel} 0 0 100% 72u "$(Win64ReqText)"
    Pop $0
    nsDialogs::Show
FunctionEnd

Function win64ReqPageLeave
FunctionEnd

Function javaRuntimePageCreate
    StrCmp $HasInstalledJava 1 0 +2
    Abort
    !insertmacro MUI_HEADER_TEXT "$(JavaRuntimePageTitle)" "$(JavaRuntimePageSubtitle)"

    nsDialogs::Create 1018
    Pop $0
    StrCmp $0 error 0 +2
    Abort

    ${NSD_CreateLabel} 0 0 100% 48u "$(JavaRuntimePageDescription)"
    Pop $0

    ${NSD_CreateCheckbox} 0 56u 100% 16u "$(InstallTemurin21Label)"
    Pop $CheckboxInstallJava
    ${NSD_SetState} $CheckboxInstallJava $InstallTemurin21

    nsDialogs::Show
FunctionEnd

Function javaRuntimePageLeave
    ${NSD_GetState} $CheckboxInstallJava $InstallTemurin21
FunctionEnd

Function detectInstalledJava
    ; Detect any installed Java runtime from PATH or standard JavaSoft registry roots.
    nsExec::ExecToStack 'powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference=''SilentlyContinue''; $ok=$false; if(Get-Command java -ErrorAction SilentlyContinue){ $ok=$true }; if(-not $ok){ $roots=@(''HKLM:\SOFTWARE\JavaSoft\JDK'',''HKLM:\SOFTWARE\WOW6432Node\JavaSoft\JDK'',''HKCU:\SOFTWARE\JavaSoft\JDK'',''HKCU:\SOFTWARE\WOW6432Node\JavaSoft\JDK'',''HKLM:\SOFTWARE\JavaSoft\JRE'',''HKLM:\SOFTWARE\WOW6432Node\JavaSoft\JRE'',''HKCU:\SOFTWARE\JavaSoft\JRE'',''HKCU:\SOFTWARE\WOW6432Node\JavaSoft\JRE''); foreach($r in $roots){ $cv=(Get-ItemProperty -Path $r -Name CurrentVersion -ErrorAction SilentlyContinue).CurrentVersion; if($cv){ $ok=$true; break } } }; if($ok){ exit 0 } else { exit 1 }"'
    Pop $0
    StrCmp $0 0 0 detect_no
    StrCpy $HasInstalledJava 1
    Return
detect_no:
    StrCpy $HasInstalledJava 0
FunctionEnd

Section "-TemurinJDK21"
    StrCmp $InstallTemurin21 ${BST_CHECKED} 0 temurin_done
    DetailPrint "Downloading recommended Java 21 runtime (x64)..."
    Delete "$TEMP\EclipseTemurinJDK21.msi"
    ; NSISdl is HTTP-only and cannot fetch GitHub HTTPS assets — use PowerShell instead.
    nsExec::ExecToStack 'powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; $ProgressPreference=''SilentlyContinue''; $dest=''$env:TEMP\EclipseTemurinJDK21.msi''; try { Invoke-WebRequest -Uri ''__HUSI_TEMURIN21_MSI_URL__'' -OutFile $dest -UseBasicParsing; if(-not (Test-Path -LiteralPath $dest)){ exit 1 }; if((Get-Item -LiteralPath $dest).Length -lt 1000000){ exit 2 }; exit 0 } catch { exit 1 }"'
    Pop $0
    Pop $1
    StrCmp $0 0 temurin_dl_ok temurin_dl_fail
temurin_dl_ok:
    DetailPrint "Installing Java 21 runtime (silent /qn; UAC may still apply)..."
    ExecWait 'msiexec /i "$TEMP\EclipseTemurinJDK21.msi" INSTALLLEVEL=1 /qn /norestart'
    Delete "$TEMP\EclipseTemurinJDK21.msi"
    Goto temurin_done
temurin_dl_fail:
    MessageBox MB_OK "Java 21 runtime download failed.$\r$\n$\r$\nInstall Java manually from:$\r$\n__HUSI_TEMURIN21_HELP_URL__"
temurin_done:
SectionEnd

; --- Install section ---
Section "$(InstallSectionName)"
    SetOutPath "$INSTDIR"
    File "/oname=${APP_NAME}.exe" "__HUSI_LAUNCHER_FILE__"
    File "/oname=LICENSE" "__HUSI_LICENSE_FILE__"
    File "/oname=desktop-java-opts.conf.template" "__HUSI_JAVA_OPTS_FILE__"
    File "/oname=desktop-java-home.conf.template" "__HUSI_JAVA_HOME_FILE__"
    File "/oname=desktop-app-args.conf.template" "__HUSI_APP_ARGS_FILE__"

    SetOutPath "$INSTDIR\app"
    File "/oname=${PACKAGE_NAME}.jar" "__HUSI_JAR_FILE__"

    SetOutPath "$INSTDIR"

    ; Uninstaller
    WriteUninstaller "$INSTDIR\uninstall.exe"

    ; Install dir registry (for upgrade detection)
    WriteRegStr HKCU "Software\${PACKAGE_NAME}\Installer" "InstallDir" "$INSTDIR"

    ; Add/Remove Programs entry
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "DisplayName" "${APP_NAME}"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "DisplayVersion" "${APP_VERSION}"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "Publisher" "${MAINTAINER}"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "URLInfoAbout" "${APP_URL}"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "UninstallString" '"$INSTDIR\uninstall.exe"'
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "QuietUninstallString" '"$INSTDIR\uninstall.exe" /S'
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "InstallLocation" "$INSTDIR"
    WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "NoModify" 1
    WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "NoRepair" 1

    ; Estimated size
    ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
    IntFmt $0 "0x%08X" $0
    WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "EstimatedSize" $0

    ; URL scheme registration
__HUSI_URL_SCHEME_REGISTRY__
SectionEnd

Function shortcutsPageCreate
    !insertmacro MUI_HEADER_TEXT "$(ShortcutPageTitle)" "$(ShortcutPageSubtitle)"

    nsDialogs::Create 1018
    Pop $0
    StrCmp $0 error 0 +2
    Abort

    ${NSD_CreateLabel} 0 0 100% 24u "$(ShortcutPageDescription)"
    Pop $0

    ${NSD_CreateCheckbox} 0 32u 100% 12u "$(DesktopShortcutLabel)"
    Pop $CheckboxDesktopShortcut
    ${NSD_SetState} $CheckboxDesktopShortcut $CreateDesktopShortcut

    ${NSD_CreateCheckbox} 0 50u 100% 12u "$(StartMenuShortcutLabel)"
    Pop $CheckboxStartMenuShortcut
    ${NSD_SetState} $CheckboxStartMenuShortcut $CreateStartMenuShortcut

    nsDialogs::Show
FunctionEnd

Function shortcutsPageLeave
    ${NSD_GetState} $CheckboxDesktopShortcut $CreateDesktopShortcut
    ${NSD_GetState} $CheckboxStartMenuShortcut $CreateStartMenuShortcut
    Call createShortcuts
FunctionEnd

Function createShortcuts
    Delete "$DESKTOP\${APP_NAME}.lnk"
    Delete "$DESKTOP\${APP_NAME_ZH_CN}.lnk"
    Delete "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk"
    Delete "$SMPROGRAMS\${APP_NAME}\${APP_NAME_ZH_CN}.lnk"
    Delete "$SMPROGRAMS\${APP_NAME}\Uninstall.lnk"
    Delete "$SMPROGRAMS\${APP_NAME}\卸载.lnk"
    RMDir "$SMPROGRAMS\${APP_NAME}"

    StrCmp $CreateDesktopShortcut ${BST_CHECKED} 0 +2
    CreateShortCut "$DESKTOP\$(ShortcutAppName).lnk" "$INSTDIR\${APP_NAME}.exe" "" "" "" "" "" "${APP_DESCRIPTION}"

    StrCmp $CreateStartMenuShortcut ${BST_CHECKED} 0 +4
    CreateDirectory "$SMPROGRAMS\${APP_NAME}"
    CreateShortCut "$SMPROGRAMS\${APP_NAME}\$(ShortcutAppName).lnk" "$INSTDIR\${APP_NAME}.exe" "" "" "" "" "" "${APP_DESCRIPTION}"
    CreateShortCut "$SMPROGRAMS\${APP_NAME}\$(UninstallShortcutName).lnk" "$INSTDIR\uninstall.exe"
FunctionEnd

Function .onInstSuccess
    IfSilent silent done
silent:
    Call createShortcuts
done:
FunctionEnd

; --- Uninstall section ---
Section "un.$(UninstallSectionName)"
    ; Remove files
    Delete "$INSTDIR\${APP_NAME}.exe"
    Delete "$INSTDIR\LICENSE"
    Delete "$INSTDIR\desktop-java-opts.conf.template"
    Delete "$INSTDIR\desktop-java-home.conf.template"
    Delete "$INSTDIR\desktop-app-args.conf.template"
    Delete "$INSTDIR\app\${PACKAGE_NAME}.jar"
    RMDir "$INSTDIR\app"
    Delete "$INSTDIR\uninstall.exe"
    Delete "$DESKTOP\${APP_NAME}.lnk"
    Delete "$DESKTOP\${APP_NAME_ZH_CN}.lnk"
    RMDir "$INSTDIR"

    ; Start Menu
    Delete "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk"
    Delete "$SMPROGRAMS\${APP_NAME}\${APP_NAME_ZH_CN}.lnk"
    Delete "$SMPROGRAMS\${APP_NAME}\Uninstall.lnk"
    Delete "$SMPROGRAMS\${APP_NAME}\$(UninstallShortcutName).lnk"
    RMDir "$SMPROGRAMS\${APP_NAME}"

    ; Registry cleanup
    DeleteRegKey HKCU "Software\${PACKAGE_NAME}\Installer"
    DeleteRegKey HKCU "Software\${PACKAGE_NAME}"
    DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}"

    ; URL schemes
__HUSI_URL_SCHEME_UNREGISTRY__
SectionEnd
