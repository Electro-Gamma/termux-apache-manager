# 🚀 Termux Apache Manager

A complete web server management solution for **Termux** on Android.

Easily start/stop Apache and PHP servers, deploy projects, generate beautiful directory indexes, and manage your webroot—all from a single command.

![Platform](https://img.shields.io/badge/Platform-Termux-blue)
![Version](https://img.shields.io/badge/Version-4.0-green)
![License](https://img.shields.io/badge/License-MIT-yellow)

---

## ✨ Features

- **Apache & PHP Control**
  - Start, stop, and restart Apache (port **8080**) and PHP (port **8081**) servers.

- **Instant Project Deployment**
  - Copy any directory to the webroot with a single command (`apache copy`).

- **Smart Index Generation**
  - Automatically creates beautiful HTML directory listings for all folders and subfolders.
  - File icons, badges, sizes, and search functionality.
  - Change detection—only regenerates when files change.

- **Interactive File Manager**
  - Remove files or directories from the webroot using an easy interactive menu.

- **Webroot Information**
  - View total size, file/directory counts, and per-item breakdown.

- **Colorful CLI**
  - Beautiful, user-friendly output with helpful status messages.

- **One-Command Installation**
  - Install the script system-wide as `apache` and automatically clean up the original file.

---

# 📦 Installation

## Prerequisites

- Termux on Android
- `pkg` package manager

## Quick Install

1. Download the script:

```bash
curl -O https://raw.githubusercontent.com/yourusername/termux-apache-manager/main/apache-manager.sh
chmod +x apache-manager.sh
```

2. Run the installer:

```bash
./apache-manager.sh install
```

The installer will:

- Install **Apache2** (required).
- Optionally install **PHP** (recommended).
- Install **nano**.
- Copy the script to:

```text
/data/data/com.termux/files/usr/bin/apache
```

- Remove the original script.

3. Reload your shell:

```bash
source ~/.bashrc
```

You can now use:

```bash
apache
```

from anywhere.

---

# 🖥️ Usage

## Interactive Menu

Launch the interactive menu:

```bash
apache
```

## Command Reference

| Command | Description |
|---------|-------------|
| `apache start` | Start Apache server |
| `apache stop` | Stop Apache (also stops PHP) |
| `apache restart` | Restart Apache |
| `apache status` | Show Apache/PHP status, ports, PIDs, and webroot information |
| `apache php start` | Start PHP server (port 8081) |
| `apache php stop` | Stop PHP server |
| `apache php restart` | Restart PHP server |
| `apache copy` | Copy current directory to the webroot and generate indexes |
| `apache remove` | Remove files/directories from webroot |
| `apache index` | Generate indexes recursively |
| `apache force` | Force regeneration of all indexes |
| `apache edit` | Edit root `index.html` using nano |
| `apache size` | Show detailed webroot size |
| `apache open` | Open the web server in your browser |
| `apache --help` | Show detailed help |
| `apache` | Interactive menu |

---

# 📚 Detailed Command Explanations

## `apache start`

Starts the Apache HTTP server on:

```
http://127.0.0.1:8080
```

Checks if Apache is already running and verifies successful startup.

---

## `apache stop`

Stops Apache and any running PHP server gracefully while cleaning up PID files.

---

## `apache restart`

Stops Apache (and PHP if running) before starting Apache again.

---

## `apache php start|stop|restart`

Controls the built-in PHP server running on **port 8081**.

Useful for testing PHP applications.

---

## `apache copy`

**The most useful command.**

Copies the current working directory into the Apache webroot.

Features:

- Automatically creates timestamped backups.
- Generates indexes recursively.
- Displays the project URL.
- Optionally opens the browser automatically.

Example:

```bash
cd ~/my-awesome-project
apache copy
```

Available at:

```
http://127.0.0.1:8080/my-awesome-project
```

---

## `apache remove`

Displays an interactive list of everything inside the webroot.

Features:

- Directories listed first
- Files listed afterward
- Delete individual items
- Delete everything with confirmation

Indexes regenerate automatically afterward.

---

## `apache index`

Generates `index.html` for the current directory and every subdirectory.

Uses change detection to avoid unnecessary regeneration.

---

## `apache force`

Force regenerates every directory index regardless of whether changes were detected.

Useful after:

- Large file updates
- Manual edits
- Cache problems

---

## `apache edit`

Opens the root `index.html` inside **nano**.

---

## `apache size`

Displays:

- Total webroot size
- Total directories
- Total files
- Per-item size breakdown

---

## `apache open`

Uses:

```bash
termux-open
```

to open:

```
http://127.0.0.1:8080
```

---

## `apache --help`

Displays:

- Command reference
- Examples
- Common workflows
- Tips

---

# 📁 File Structure

| Path | Description |
|------|-------------|
| `/data/data/com.termux/files/usr/bin/apache` | Installed executable |
| `$HOME/.apache_manager/` | Manager data |
| `$HOME/.apache_manager/logs/` | Apache and PHP logs |
| `$HOME/.apache_manager/cache/` | Index cache |
| `$HOME/.apache_manager/watch/` | Change detection data |
| `$PREFIX/share/apache2/default-site/htdocs/` | Apache webroot |

---

# 💡 Common Workflows

## Quick Start

```bash
apache start
apache status
apache open
```

---

## Deploy a Project

```bash
cd ~/my-project
apache copy
```

---

## Update a Project

```bash
cd ~/my-project
apache copy
```

Previous versions are automatically backed up.

---

## Remove a Project

```bash
apache remove
```

---

## Regenerate Indexes

```bash
cd ~/my-project
apache index
```

---

# 🛠️ Tips & Notes

- Running `apache` without arguments opens the interactive menu.
- PHP is optional but recommended.
- Apache runs on **8080**.
- PHP runs on **8081**.
- Backup folders are named:

```
*_backup_YYYYMMDD_HHMMSS
```

and are hidden from generated indexes.

- Change detection prevents unnecessary regeneration.
- Press **Ctrl + /** inside generated index pages to focus the search box.
- Logs are stored in:

```
~/.apache_manager/logs/
```

---

# 🤝 Contributing

Contributions are welcome.

1. Fork the repository.
2. Create a feature branch.

```bash
git checkout -b feature/AmazingFeature
```

3. Commit your changes.

```bash
git commit -m "Add some AmazingFeature"
```

4. Push your branch.

```bash
git push origin feature/AmazingFeature
```

5. Open a Pull Request.

---

# 📄 License

This project is licensed under the **MIT License**.

See the `LICENSE` file for details.

---

# 🙏 Acknowledgements

- Built for **Termux**, the powerful Android terminal emulator.
- Uses the Apache and PHP packages available through `pkg`.

---

## Happy Coding!

🚀
