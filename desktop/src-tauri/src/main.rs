// Windows shell around the hosted CloseBy web client: lives in the tray, starts
// with Windows and keeps running when the window is closed, so calls still ring.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use tauri::menu::{CheckMenuItem, Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, WindowEvent};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};

/// Passed by the autostart entry: start in the tray instead of opening the window.
const HIDDEN_ARG: &str = "--hidden";

fn show(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

fn main() {
    tauri::Builder::default()
        // a second start (e.g. from the start menu) just brings the window up
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| show(app)))
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, Some(vec![HIDDEN_ARG])))
        .setup(|app| {
            // autostart is on after installation; afterwards the tray entry decides
            let marker = app.path().app_config_dir()?.join("autostart-initialized");
            if !marker.exists() {
                let _ = app.autolaunch().enable();
                std::fs::create_dir_all(marker.parent().unwrap())?;
                std::fs::write(&marker, b"")?;
            }

            let open = MenuItem::with_id(app, "open", "Öffnen", true, None::<&str>)?;
            let autostart = CheckMenuItem::with_id(
                app,
                "autostart",
                "Mit Windows starten",
                true,
                app.autolaunch().is_enabled().unwrap_or(false),
                None::<&str>,
            )?;
            let quit = MenuItem::with_id(app, "quit", "Beenden", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &autostart, &quit])?;

            TrayIconBuilder::new()
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("CloseBy")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(move |app, event| match event.id.as_ref() {
                    "open" => show(app),
                    "quit" => app.exit(0),
                    "autostart" => {
                        let launcher = app.autolaunch();
                        let enabled = launcher.is_enabled().unwrap_or(false);
                        let _ = if enabled { launcher.disable() } else { launcher.enable() };
                        let _ = autostart.set_checked(!enabled);
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        show(tray.app_handle());
                    }
                })
                .build(app)?;

            if !std::env::args().any(|arg| arg == HIDDEN_ARG) {
                show(app.handle());
            }
            Ok(())
        })
        // closing the window keeps CloseBy in the tray, otherwise calls would not ring
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running CloseBy");
}
