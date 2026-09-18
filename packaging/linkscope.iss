; Inno Setup script for LinkScope.
; Driven by the Gradle task `innoSetup`, which passes the version and folders as defines;
; the defaults below let it also compile from the Inno Setup IDE after `gradlew jpackageImage`.

#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\build\jpackage\LinkScope"
#endif
#ifndef OutputDir
  #define OutputDir "..\build\installer"
#endif

[Setup]
AppId={{6F0B2A3C-9D4E-4B7A-8C1F-2A5D7E9B3C41}
AppName=LinkScope
AppVersion={#AppVersion}
AppVerName=LinkScope {#AppVersion}
AppPublisher=LinkScope
DefaultDirName={autopf}\LinkScope
DefaultGroupName=LinkScope
UninstallDisplayIcon={app}\LinkScope.exe
SetupIconFile=linkscope.ico
OutputDir={#OutputDir}
OutputBaseFilename=LinkScope-{#AppVersion}-setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; Unsigned app: default to a per-user install (no UAC prompt), but let the user pick machine-wide.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
CloseApplications=yes
DisableProgramGroupPage=yes

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
#ifdef NpcapInstaller
; Offered only when no Npcap is present on the machine. The Npcap installer shows its own wizard
; and asks for elevation itself; it is not run silently.
Name: "npcap"; Description: "Install Npcap {#NpcapVersion} (packet capture driver, used only by the Capture tool)"; GroupDescription: "Drivers:"; Check: not NpcapInstalled
#endif

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
#ifdef NpcapInstaller
Source: "{#NpcapInstaller}"; DestDir: "{tmp}"; DestName: "npcap-setup.exe"; Flags: deleteafterinstall; Tasks: npcap
#endif

[Icons]
Name: "{group}\LinkScope"; Filename: "{app}\LinkScope.exe"
Name: "{group}\Uninstall LinkScope"; Filename: "{uninstallexe}"
Name: "{autodesktop}\LinkScope"; Filename: "{app}\LinkScope.exe"; Tasks: desktopicon

[Run]
#ifdef NpcapInstaller
Filename: "{tmp}\npcap-setup.exe"; StatusMsg: "Installing Npcap (follow its wizard)..."; Tasks: npcap; Flags: waituntilterminated shellexec
#endif
Filename: "{app}\LinkScope.exe"; Description: "{cm:LaunchProgram,LinkScope}"; Flags: nowait postinstall skipifsilent

[Code]
function NpcapInstalled: Boolean;
begin
  Result := FileExists(ExpandConstant('{sys}\Npcap\wpcap.dll'))
    or RegKeyExists(HKEY_LOCAL_MACHINE, 'SOFTWARE\Npcap')
    or RegKeyExists(HKEY_LOCAL_MACHINE, 'SOFTWARE\WOW6432Node\Npcap');
end;
