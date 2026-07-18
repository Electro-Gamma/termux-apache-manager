#!/data/data/com.termux/files/usr/bin/bash

# ============================================
# TERMUX APACHE MANAGER - FULL GENERATION
# Version: 4.0 (Generate All Subdirectories)
# ============================================

# ===== INSTALLATION SECTION =====
INSTALL_SCRIPT_NAME="apache"
SCRIPT_PATH="$(realpath "$0")"
SCRIPT_DIR="$(dirname "$SCRIPT_PATH")"
SCRIPT_BASENAME="$(basename "$SCRIPT_PATH")"

install_script() {
    echo -e "${BLUE}🔧 Installing Termux Apache Manager...${NC}"
    echo ""
    
    # Check for required packages
    echo -e "${BLUE}Checking dependencies...${NC}"
    
    # Check Apache (required)
    if ! command -v apachectl &> /dev/null; then
        echo -e "${YELLOW}⚠ Apache not found. Installing...${NC}"
        pkg install apache2 -y
    else
        echo -e "${GREEN}✓ Apache already installed${NC}"
    fi
    
    # Check PHP (optional but recommended)
    if ! command -v php &> /dev/null; then
        echo -e "${YELLOW}⚠ PHP not found. Optional but recommended for PHP support.${NC}"
        read -p "Install PHP? (y/n): " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            pkg install php -y
        fi
    else
        echo -e "${GREEN}✓ PHP already installed${NC}"
    fi
    
    # Check nano (for editing)
    if ! command -v nano &> /dev/null; then
        echo -e "${YELLOW}⚠ Nano not found. Installing...${NC}"
        pkg install nano -y
    else
        echo -e "${GREEN}✓ Nano already installed${NC}"
    fi
    
    echo ""
    
    # Remove old installation if exists
    if [ -f "/data/data/com.termux/files/usr/bin/$INSTALL_SCRIPT_NAME" ]; then
        echo -e "${YELLOW}⚠ Removing old installation: $INSTALL_SCRIPT_NAME${NC}"
        rm -f "/data/data/com.termux/files/usr/bin/$INSTALL_SCRIPT_NAME"
    fi
    
    # Install script to /data/data/com.termux/files/usr/bin
    echo -e "${BLUE}[+] Installing script as '$INSTALL_SCRIPT_NAME' in /data/data/com.termux/files/usr/bin...${NC}"
    
    # Copy the entire script to the bin directory
    cp "$SCRIPT_PATH" "/data/data/com.termux/files/usr/bin/$INSTALL_SCRIPT_NAME"
    chmod 755 "/data/data/com.termux/files/usr/bin/$INSTALL_SCRIPT_NAME"
    
    echo -e "${GREEN}✓ Script installed as '$INSTALL_SCRIPT_NAME'${NC}"
    
    # Remove the original script if it exists and has a different name
    if [ -f "$SCRIPT_PATH" ] && [ "$SCRIPT_BASENAME" != "$INSTALL_SCRIPT_NAME" ]; then
        echo -e "${YELLOW}⚠ Removing original script: $SCRIPT_BASENAME${NC}"
        rm -f "$SCRIPT_PATH"
        echo -e "${GREEN}✓ Original script removed${NC}"
    fi
    
    echo ""
    echo -e "${GREEN}✅ Installation complete!${NC}"
    echo -e "${BLUE}You can now use the 'apache' command from anywhere:${NC}"
    echo "  apache        # Interactive menu"
    echo "  apache start  # Start Apache"
    echo "  apache stop   # Stop Apache"
    echo "  apache status # Show status"
    echo ""
    echo -e "${YELLOW}Note: You may need to restart your terminal or run:${NC}"
    echo "  source ~/.bashrc"
    echo ""
    
    # Ask if user wants to run the script now
    read -p "Run Apache Manager now? (y/n): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        exec "/data/data/com.termux/files/usr/bin/$INSTALL_SCRIPT_NAME"
    fi
    
    exit 0
}

# ===== CHECK IF INSTALLATION IS REQUESTED =====
if [ "$1" = "install" ]; then
    # Source colors for installation
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    BLUE='\033[0;34m'
    NC='\033[0m'
    install_script
    exit 0
fi

# Check if script is installed as 'apache'
if [ "$SCRIPT_BASENAME" != "$INSTALL_SCRIPT_NAME" ] && [ "$SCRIPT_BASENAME" != "apache-manager.sh" ]; then
    echo -e "${YELLOW}⚠ Not installed. Run with 'install' to install:${NC}"
    echo "  $SCRIPT_BASENAME install"
    echo ""
    echo -e "${BLUE}Or run directly with:${NC}"
    echo "  $SCRIPT_BASENAME [command]"
    echo ""
fi

# ===== VARIABLES =====
WEBROOT="$PREFIX/share/apache2/default-site/htdocs"
INDEX="$WEBROOT/index.html"
LOCAL_URL="http://127.0.0.1:8080"
PHP_PORT=8081

# ===== APACHE MANAGER HOME DIRECTORY =====
APACHE_MANAGER_HOME="$HOME/.apache_manager"
PHP_PID="$APACHE_MANAGER_HOME/php.pid"
LOG_DIR="$APACHE_MANAGER_HOME/logs"
INDEX_CACHE="$APACHE_MANAGER_HOME/cache"
FILE_WATCH="$APACHE_MANAGER_HOME/watch"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# ===== INITIALIZATION =====
init_directories() {
    mkdir -p "$WEBROOT" "$LOG_DIR" "$INDEX_CACHE" "$FILE_WATCH"
}

# ===== CALL INITIALIZATION =====
init_directories

# ===== HELP FUNCTION =====
show_help() {
    echo ""
    echo -e "${BLUE}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║          🚀 TERMUX APACHE MANAGER - HELP               ║${NC}"
    echo -e "${BLUE}╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${GREEN}📋 COMMAND REFERENCE${NC}"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    
    echo -e "${YELLOW}📌 SERVER MANAGEMENT${NC}"
    echo "  ┌─────────────────────────────────────────────────────────────────┐"
    echo -e "  │ ${GREEN}apache start${NC}      │ Start Apache web server on port 8080                │"
    echo -e "  │ ${GREEN}apache stop${NC}       │ Stop Apache (also stops PHP if running)            │"
    echo -e "  │ ${GREEN}apache restart${NC}    │ Restart Apache completely                           │"
    echo -e "  │ ${GREEN}apache status${NC}     │ Show server status (Apache/PHP, PIDs, ports)       │"
    echo "  └─────────────────────────────────────────────────────────────────┘"
    echo ""
    
    echo -e "${YELLOW}🐘 PHP MANAGEMENT${NC}"
    echo "  ┌─────────────────────────────────────────────────────────────────┐"
    echo -e "  │ ${GREEN}apache php start${NC}  │ Start PHP server on port 8081                       │"
    echo -e "  │ ${GREEN}apache php stop${NC}   │ Stop PHP server                                     │"
    echo -e "  │ ${GREEN}apache php restart${NC}│ Restart PHP server                                  │"
    echo -e "  │ ${GREEN}apache php ps${NC}     │ Show running PHP processes                          │"
    echo -e "  │ ${GREEN}apache php kill${NC}   │ Kill all PHP processes (by port or PID)            │"
    echo "  └─────────────────────────────────────────────────────────────────┘"
    echo ""
    
    echo -e "${YELLOW}📂 FILE & DIRECTORY MANAGEMENT${NC}"
    echo "  ┌─────────────────────────────────────────────────────────────────┐"
    echo -e "  │ ${GREEN}apache copy${NC}       │ Copy current directory to webroot with indexes      │"
    echo -e "  │ ${GREEN}apache remove${NC}     │ Interactive menu to remove files/directories        │"
    echo -e "  │ ${GREEN}apache index${NC}      │ Generate indexes for current dir + subdirs          │"
    echo -e "  │ ${GREEN}apache force${NC}      │ Force regenerate ALL indexes recursively            │"
    echo -e "  │ ${GREEN}apache edit${NC}       │ Edit index.html in nano editor                      │"
    echo -e "  │ ${GREEN}apache size${NC}       │ Show webroot size with detailed breakdown           │"
    echo "  └─────────────────────────────────────────────────────────────────┘"
    echo ""
    
    echo -e "${YELLOW}🌐 UTILITY COMMANDS${NC}"
    echo "  ┌─────────────────────────────────────────────────────────────────┐"
    echo -e "  │ ${GREEN}apache open${NC}       │ Open web server in browser (Termux)                 │"
    echo -e "  │ ${GREEN}apache ports${NC}      │ Show all PHP processes and ports                    │"
    echo -e "  │ ${GREEN}apache${NC}            │ Open interactive menu (no arguments)                │"
    echo -e "  │ ${GREEN}apache --help${NC}     │ Show this help message                               │"
    echo -e "  │ ${GREEN}apache install${NC}    │ Install script system-wide                          │"
    echo "  └─────────────────────────────────────────────────────────────────┘"
    echo ""
    
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""
    
    echo -e "${YELLOW}📖 DETAILED COMMAND EXPLANATIONS${NC}"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    
    echo -e "${GREEN}1. apache start${NC}"
    echo "   └─ Starts Apache HTTP server on 127.0.0.1:8080"
    echo "      • Checks if Apache is already running"
    echo "      • Starts Apache daemon"
    echo "      • Verifies successful start"
    echo ""
    
    echo -e "${GREEN}2. apache stop${NC}"
    echo "   └─ Stops Apache and any running PHP server"
    echo "      • Gracefully stops PHP first (if running)"
    echo "      • Stops Apache daemon"
    echo "      • Cleans up PID files"
    echo ""
    
    echo -e "${GREEN}3. apache restart${NC}"
    echo "   └─ Performs a full restart of Apache"
    echo "      • Stops Apache (and PHP)"
    echo "      • Starts Apache fresh"
    echo ""
    
    echo -e "${GREEN}4. apache status${NC}"
    echo "   └─ Shows comprehensive server status"
    echo "      • Apache: Running/Stopped with PID"
    echo "      • PHP: Running/Stopped with PID"
    echo "      • Webroot: Size, file count, directory count"
    echo ""
    
    echo -e "${GREEN}5. apache php start|stop|restart${NC}"
    echo "   └─ Controls the PHP built-in server on port 8081"
    echo "      • Useful for testing PHP files"
    echo "      • Runs separately from Apache"
    echo ""
    
    echo -e "${GREEN}6. apache php ps${NC}"
    echo "   └─ Show all running PHP processes"
    echo "      • Displays PID, port, and command"
    echo "      • Helps identify multiple PHP instances"
    echo ""
    echo "      Example output:"
    echo "      PID 9429  php -S 127.0.0.1:8081"
    echo "      PID 30929 php -S 127.0.0.1:8082"
    echo ""
    
    echo -e "${GREEN}7. apache php kill${NC}"
    echo "   └─ Kill all PHP processes interactively"
    echo "      • Shows all running PHP processes"
    echo "      • Option to kill by PID or port"
    echo "      • Can kill all PHP processes at once"
    echo ""
    echo "      Manual kill commands:"
    echo "      kill <PID>                 # Kill by PID"
    echo "      pkill -f \"php -S 127.0.0.1:8081\"  # Kill by port"
    echo "      pkill -f \"php -S 127.0.0.1:8082\"  # Kill by port"
    echo ""
    
    echo -e "${GREEN}8. apache ports${NC}"
    echo "   └─ Show all PHP processes with their ports"
    echo "      • Uses ps and grep to find PHP processes"
    echo "      • Shows which ports are in use"
    echo "      • Can use nmap for verification"
    echo ""
    echo "      Verification commands:"
    echo "      ps -ef | grep php          # List PHP processes"
    echo "      nmap 127.0.0.1             # Scan local ports"
    echo ""
    
    echo -e "${GREEN}9. apache copy${NC} ⭐ MOST USEFUL COMMAND"
    echo "   └─ Copies your current working directory to webroot"
    echo "      • Creates backup if directory already exists"
    echo "      • Generates index.html for all subdirectories"
    echo "      • Shows access URL"
    echo "      • Option to open in browser"
    echo ""
    echo "      Example:"
    echo "      cd ~/my-website"
    echo "      apache copy"
    echo "      # Access at: http://127.0.0.1:8080/my-website"
    echo ""
    
    echo -e "${GREEN}10. apache remove${NC}"
    echo "    └─ Interactive file removal tool"
    echo "       • Shows all items in webroot with numbers"
    echo "       • Select items to remove"
    echo "       • Option to remove all with confirmation"
    echo "       • Regenerates indexes after removal"
    echo ""
    
    echo -e "${GREEN}11. apache index${NC}"
    echo "    └─ Generates directory indexes for current directory and subdirs"
    echo "       • Creates beautiful HTML file listings"
    echo "       • Detects changes before regenerating"
    echo "       • Shows file icons, badges, sizes"
    echo ""
    
    echo -e "${GREEN}12. apache force${NC}"
    echo "    └─ Force regenerates ALL indexes recursively"
    echo "       • Ignores change detection"
    echo "       • Useful after bulk file changes"
    echo "       • Regenerates everything from scratch"
    echo ""
    
    echo -e "${GREEN}13. apache edit${NC}"
    echo "    └─ Opens root index.html in nano editor"
    echo "       • For manual edits"
    echo "       • Changes persist until regenerated"
    echo ""
    
    echo -e "${GREEN}14. apache size${NC}"
    echo "    └─ Shows detailed size information"
    echo "       • Total webroot size"
    echo "       • File and directory counts"
    echo "       • Size breakdown per item"
    echo ""
    
    echo -e "${GREEN}15. apache open${NC}"
    echo "    └─ Opens web server in browser"
    echo "       • Uses termux-open command"
    echo "       • Opens http://127.0.0.1:8080"
    echo ""
    
    echo -e "${GREEN}16. apache${NC} (no arguments)"
    echo "    └─ Opens full interactive menu with all options"
    echo "       • Easy to use menu interface"
    echo "       • Shows server status at top"
    echo ""
    
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""
    
    echo -e "${YELLOW}💡 COMMON WORKFLOWS${NC}"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    
    echo -e "${GREEN}Quick Start:${NC}"
    echo "  apache start      # Start server"
    echo "  apache status     # Verify it's running"
    echo "  apache open       # Open in browser"
    echo ""
    
    echo -e "${GREEN}Deploy a Project:${NC}"
    echo "  cd ~/my-project"
    echo "  apache copy       # Copy to webroot with indexes"
    echo ""
    
    echo -e "${GREEN}Update a Deployed Project:${NC}"
    echo "  cd ~/my-project"
    echo "  apache copy       # Will backup old version and deploy new"
    echo ""
    
    echo -e "${GREEN}Remove a Project:${NC}"
    echo "  apache remove     # Interactive menu"
    echo "  # Select the project number to remove"
    echo ""
    
    echo -e "${GREEN}Generate Indexes Only:${NC}"
    echo "  cd ~/my-project"
    echo "  apache index      # Generate indexes for current dir + subdirs"
    echo ""
    
    echo -e "${GREEN}Force Regenerate Everything:${NC}"
    echo "  apache force      # Regenerate ALL indexes recursively"
    echo ""
    
    echo -e "${GREEN}Manage PHP Processes:${NC}"
    echo "  apache php ps     # Show all PHP processes"
    echo "  apache php kill   # Kill PHP processes interactively"
    echo "  apache ports      # Show PHP processes with ports"
    echo ""
    
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""
    
    echo -e "${YELLOW}📝 TIPS & NOTES${NC}"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    echo "  • No arguments = Interactive menu (apache)"
    echo "  • PHP is optional but recommended for PHP files"
    echo "  • Port 8080 = Apache, Port 8081 = PHP"
    echo "  • Backup folders are automatically created when copying"
    echo "  • Change detection prevents unnecessary index regeneration"
    echo "  • Ctrl+/ in generated index pages activates search"
    echo "  • All logs stored in ~/.apache_manager/logs/"
    echo "  • Webroot location: $WEBROOT"
    echo ""
    echo "  PHP Process Management:"
    echo "  • ps -ef | grep php              # List all PHP processes"
    echo "  • kill <PID>                      # Kill by PID"
    echo "  • pkill -f \"php -S 127.0.0.1:8081\"  # Kill by port"
    echo "  • pkill -f \"php -S 127.0.0.1:8082\"  # Kill by port"
    echo "  • nmap 127.0.0.1                  # Scan local ports"
    echo ""
    
    echo -e "${GREEN}For more information, visit the interactive menu:${NC}"
    echo "  apache"
    echo ""
}

# ===== PHP MANAGEMENT FUNCTIONS =====
php_show_processes() {
    echo ""
    echo -e "${BLUE}📊 Running PHP Processes${NC}"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    
    local php_processes=$(ps -ef | grep "php -S" | grep -v grep)
    
    if [ -z "$php_processes" ]; then
        echo -e "${YELLOW}⚠ No PHP processes found${NC}"
        echo ""
        return 0
    fi
    
    echo "$php_processes" | while read -r line; do
        local pid=$(echo "$line" | awk '{print $2}')
        local cmd=$(echo "$line" | awk '{for(i=8;i<=NF;i++) printf "%s ", $i; print ""}')
        local port=$(echo "$cmd" | grep -o "127.0.0.1:[0-9]*" | cut -d':' -f2)
        
        if [ -n "$port" ]; then
            echo -e "  ${GREEN}PID:${NC} $pid  ${BLUE}Port:${NC} $port  ${YELLOW}Command:${NC} $cmd"
        else
            echo -e "  ${GREEN}PID:${NC} $pid  ${YELLOW}Command:${NC} $cmd"
        fi
    done
    
    echo ""
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""
    echo -e "${YELLOW}💡 To stop specific PHP processes:${NC}"
    echo "  kill <PID>                     # Kill by PID"
    echo "  pkill -f \"php -S 127.0.0.1:8081\"  # Kill by port"
    echo "  pkill -f \"php -S 127.0.0.1:8082\"  # Kill by port"
    echo ""
    echo -e "${YELLOW}💡 To verify ports:${NC}"
    echo "  nmap 127.0.0.1"
    echo ""
}

php_kill_menu() {
    while true; do
        clear
        echo "=============================="
        echo "🔫 PHP Process Killer"
        echo "=============================="
        echo ""
        
        local php_processes=$(ps -ef | grep "php -S" | grep -v grep)
        
        if [ -z "$php_processes" ]; then
            echo -e "${YELLOW}⚠ No PHP processes found${NC}"
            echo ""
            read -p "Press Enter to continue..."
            return 0
        fi
        
        echo -e "${BLUE}Running PHP Processes:${NC}"
        echo ""
        
        local count=0
        declare -a pids=()
        declare -a ports=()
        
        echo "$php_processes" | while read -r line; do
            local pid=$(echo "$line" | awk '{print $2}')
            local cmd=$(echo "$line" | awk '{for(i=8;i<=NF;i++) printf "%s ", $i; print ""}')
            local port=$(echo "$cmd" | grep -o "127.0.0.1:[0-9]*" | cut -d':' -f2)
            
            count=$((count + 1))
            pids[$count]=$pid
            ports[$count]=$port
            
            if [ -n "$port" ]; then
                echo "  $count) PID: $pid  Port: $port"
            else
                echo "  $count) PID: $pid"
            fi
        done
        
        echo ""
        echo "a) Kill ALL PHP processes"
        echo "0) Exit"
        echo ""
        read -p "Select process to kill (number) or option: " choice
        
        case "$choice" in
            0) break ;;
            a|A)
                echo ""
                read -p "Type 'yes' to confirm killing ALL PHP processes: " confirm
                if [ "$confirm" = "yes" ]; then
                    for pid in "${pids[@]}"; do
                        if [ -n "$pid" ]; then
                            kill "$pid" 2>/dev/null
                            echo -e "${GREEN}✓ Killed PID: $pid${NC}"
                        fi
                    done
                    echo -e "${GREEN}✓ All PHP processes killed${NC}"
                fi
                sleep 2
                ;;
            *)
                if [ -n "${pids[$choice]}" ]; then
                    local pid="${pids[$choice]}"
                    local port="${ports[$choice]}"
                    kill "$pid" 2>/dev/null
                    if [ -n "$port" ]; then
                        echo -e "${GREEN}✓ Killed PHP process on port $port (PID: $pid)${NC}"
                    else
                        echo -e "${GREEN}✓ Killed PHP process (PID: $pid)${NC}"
                    fi
                    sleep 1
                else
                    echo -e "${RED}✗ Invalid choice${NC}"
                    sleep 1
                fi
                ;;
        esac
    done
}

show_ports() {
    echo ""
    echo -e "${BLUE}📊 PHP Ports & Processes${NC}"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    
    php_show_processes
    
    echo -e "${YELLOW}💡 To scan all local ports:${NC}"
    echo "  nmap 127.0.0.1"
    echo ""
    echo -e "${YELLOW}💡 To check specific ports:${NC}"
    echo "  netstat -tulpn | grep php"
    echo "  lsof -i :8081"
    echo ""
}

# ===== APACHE FUNCTIONS =====
apache_running() {
    pgrep httpd >/dev/null 2>&1
}

start_apache() {
    if apache_running; then
        echo -e "${YELLOW}⚠ Apache already running${NC}"
        return 0
    fi
    echo -e "${BLUE}[+] Starting Apache...${NC}"
    apachectl start 2>/dev/null
    sleep 2
    if apache_running; then
        echo -e "${GREEN}✓ Apache started${NC}"
    else
        echo -e "${RED}✗ Failed to start Apache${NC}"
    fi
}

stop_apache() {
    if ! apache_running; then
        echo -e "${YELLOW}⚠ Apache not running${NC}"
        return 0
    fi
    echo -e "${BLUE}[+] Stopping Apache and PHP...${NC}"
    
    if php_running; then
        kill "$(cat "$PHP_PID")" 2>/dev/null
        rm -f "$PHP_PID"
        echo -e "${GREEN}✓ PHP stopped${NC}"
    fi
    
    apachectl stop 2>/dev/null
    echo -e "${GREEN}✓ Apache stopped${NC}"
}

restart_apache() {
    stop_apache
    start_apache
}

# ===== PHP FUNCTIONS =====
php_running() {
    [ -f "$PHP_PID" ] && kill -0 "$(cat "$PHP_PID")" 2>/dev/null
}

start_php() {
    if php_running; then
        echo -e "${YELLOW}⚠ PHP already running${NC}"
        return 0
    fi
    echo -e "${BLUE}[+] Starting PHP on port $PHP_PORT...${NC}"
    cd "$WEBROOT"
    php -S 127.0.0.1:$PHP_PORT > "$LOG_DIR/php.log" 2>&1 &
    echo $! > "$PHP_PID"
    echo -e "${GREEN}✓ PHP started (PID: $(cat $PHP_PID))${NC}"
}

stop_php() {
    if ! php_running; then
        echo -e "${YELLOW}⚠ PHP not running${NC}"
        return 0
    fi
    kill "$(cat "$PHP_PID")" 2>/dev/null
    rm -f "$PHP_PID"
    echo -e "${GREEN}✓ PHP stopped${NC}"
}

restart_php() {
    stop_php
    start_php
}

# ===== GET FILE ICON =====
get_file_icon() {
    local ext="$1"
    case "$ext" in
        php) echo "🐘" ;;
        html|htm) echo "🌐" ;;
        css) echo "🎨" ;;
        js) echo "📜" ;;
        jpg|jpeg|png|gif|svg|webp|ico) echo "🖼️" ;;
        pdf) echo "📕" ;;
        zip|tar|gz|rar|7z) echo "📦" ;;
        sh|bash|zsh) echo "⚡" ;;
        py) echo "🐍" ;;
        jsx|ts|tsx) echo "⚛️" ;;
        json) echo "📋" ;;
        xml) echo "📋" ;;
        txt|md|log) echo "📝" ;;
        mp3|wav|flac) echo "🎵" ;;
        mp4|avi|mkv|mov) echo "🎬" ;;
        *) echo "📄" ;;
    esac
}

# ===== GET FILE BADGE =====
get_file_badge() {
    local ext="$1"
    case "$ext" in
        php) echo "PHP" ;;
        html|htm) echo "HTML" ;;
        css) echo "CSS" ;;
        js) echo "JS" ;;
        jpg|jpeg|png|gif|svg|webp|ico) echo "IMAGE" ;;
        pdf) echo "PDF" ;;
        zip|tar|gz|rar|7z) echo "ARCHIVE" ;;
        sh|bash|zsh) echo "SCRIPT" ;;
        py) echo "PYTHON" ;;
        jsx|ts|tsx) echo "REACT" ;;
        json) echo "JSON" ;;
        xml) echo "XML" ;;
        txt|md|log) echo "TEXT" ;;
        mp3|wav|flac) echo "AUDIO" ;;
        mp4|avi|mkv|mov) echo "VIDEO" ;;
        *) echo "FILE" ;;
    esac
}

# ===== CHECK IF ITEM SHOULD BE HIDDEN =====
is_hidden() {
    local name="$1"
    
    # Hide backup folders (ending with _backup_*)
    if [[ "$name" =~ _backup_[0-9]{8}_[0-9]{6}$ ]]; then
        return 0
    fi
    
    # Hide index.html (our generated file)
    if [ "$name" = "index.html" ]; then
        return 0
    fi
    
    # Hide index.html.backup
    if [ "$name" = "index.html.backup" ]; then
        return 0
    fi
    
    return 1
}

# ===== RENAME EXISTING INDEX.HTML =====
RENAMED_INDEX=false
RENAMED_TO=""

rename_existing_index() {
    local dir="$1"
    local index_file="$dir/index.html"
    
    RENAMED_INDEX=false
    RENAMED_TO=""
    
    if [ -f "$index_file" ]; then
        if grep -q "Generated by Termux Apache Manager" "$index_file" 2>/dev/null; then
            rm -f "$index_file"
            echo -e "${YELLOW}  Removed old generated index.html in $(basename "$dir")${NC}"
        else
            local counter=1
            local new_name="index1.html"
            
            while [ -f "$dir/$new_name" ]; do
                counter=$((counter + 1))
                new_name="index${counter}.html"
            done
            
            mv "$index_file" "$dir/$new_name"
            RENAMED_INDEX=true
            RENAMED_TO="$new_name"
            echo -e "${YELLOW}  Renamed original index.html → $new_name in $(basename "$dir")${NC}"
        fi
    fi
}

# ===== GENERATE INDEX HTML =====
generate_index_html() {
    local target_dir="$1"
    local output_file="$2"
    local title="$3"
    local path_display="$4"
    
    [ -f "$output_file" ] && cp "$output_file" "$output_file.backup"
    
    local redirect_target=""
    if [ "$RENAMED_INDEX" = true ] && [ -n "$RENAMED_TO" ]; then
        redirect_target="$RENAMED_TO"
    fi
    
    cat > "$output_file" <<EOF
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
EOF

    if [ -n "$redirect_target" ]; then
        cat >> "$output_file" <<EOF
    <meta http-equiv="refresh" content="0; url=$redirect_target">
    <title>Redirecting to $redirect_target...</title>
EOF
    else
        cat >> "$output_file" <<EOF
    <title>📁 $title - Termux Apache</title>
EOF
    fi

    cat >> "$output_file" <<'EOF'
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            padding: 20px;
        }
        .container {
            max-width: 1200px;
            margin: 0 auto;
            background: rgba(255, 255, 255, 0.95);
            border-radius: 20px;
            padding: 30px;
            box-shadow: 0 20px 60px rgba(0,0,0,0.3);
        }
        .header {
            border-bottom: 3px solid #667eea;
            padding-bottom: 15px;
            margin-bottom: 25px;
        }
        h1 {
            color: #2d3748;
            font-size: 28px;
            display: flex;
            align-items: center;
            gap: 10px;
        }
        .path-info {
            color: #718096;
            font-size: 14px;
            margin-top: 5px;
        }
        .stats {
            background: #f7fafc;
            border-radius: 10px;
            padding: 15px;
            margin: 20px 0;
            display: flex;
            gap: 20px;
            flex-wrap: wrap;
            border-left: 4px solid #667eea;
        }
        .stat-item {
            font-size: 14px;
        }
        .stat-item strong { color: #2d3748; }
        .search-box {
            width: 100%;
            padding: 12px 20px;
            border: 2px solid #e2e8f0;
            border-radius: 10px;
            font-size: 16px;
            margin-bottom: 20px;
            transition: border-color 0.3s;
        }
        .search-box:focus {
            outline: none;
            border-color: #667eea;
        }
        .file-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
            gap: 12px;
            margin-top: 15px;
        }
        .file-item {
            background: #f7fafc;
            padding: 12px 16px;
            border-radius: 10px;
            border: 1px solid #e2e8f0;
            display: flex;
            align-items: center;
            gap: 12px;
            transition: all 0.2s;
            text-decoration: none;
            color: #2d3748;
        }
        .file-item:hover {
            transform: translateY(-2px);
            box-shadow: 0 8px 25px rgba(0,0,0,0.1);
            border-color: #667eea;
            background: white;
        }
        .file-item .icon {
            font-size: 28px;
            min-width: 35px;
            text-align: center;
        }
        .file-item .name {
            flex: 1;
            font-weight: 500;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .file-item .badge {
            font-size: 11px;
            padding: 2px 10px;
            border-radius: 20px;
            font-weight: 600;
            background: #e2e8f0;
            color: #4a5568;
        }
        .badge-dir { background: #bee3f8; color: #2b6cb0; }
        .badge-php { background: #e9d8fd; color: #553c9a; }
        .badge-html { background: #fbd38d; color: #744210; }
        .badge-css { background: #feb2b2; color: #742a2a; }
        .badge-js { background: #fefcbf; color: #744210; }
        .badge-img { background: #f687b3; color: #702459; }
        .badge-pdf { background: #fc8181; color: #742a2a; }
        .badge-zip { background: #b2f5ea; color: #234e52; }
        .badge-sh { background: #9ae6b4; color: #22543d; }
        .badge-py { background: #c6f6d5; color: #22543d; }
        .badge-text { background: #e2e8f0; color: #4a5568; }
        .footer {
            margin-top: 25px;
            padding-top: 20px;
            border-top: 1px solid #e2e8f0;
            color: #a0aec0;
            font-size: 13px;
            text-align: center;
        }
        .back-link {
            display: inline-block;
            padding: 8px 16px;
            background: #667eea;
            color: white;
            text-decoration: none;
            border-radius: 8px;
            margin-bottom: 15px;
        }
        .back-link:hover { background: #5a67d8; }
        .sort-info {
            font-size: 12px;
            color: #a0aec0;
            margin-top: 5px;
        }
        .generated-info {
            font-size: 12px;
            color: #a0aec0;
            text-align: right;
            margin-top: 5px;
        }
        .breadcrumb {
            color: #718096;
            font-size: 14px;
            padding: 10px 0;
            margin-bottom: 15px;
            border-bottom: 1px solid #e2e8f0;
        }
        .breadcrumb a {
            color: #667eea;
            text-decoration: none;
        }
        .breadcrumb a:hover { text-decoration: underline; }
        .redirect-info {
            background: #ebf8ff;
            border: 1px solid #bee3f8;
            border-radius: 8px;
            padding: 12px;
            margin: 15px 0;
            color: #2b6cb0;
            font-size: 14px;
        }
        .redirect-info code {
            background: #bee3f8;
            padding: 2px 8px;
            border-radius: 4px;
            font-weight: bold;
        }
        .redirecting {
            text-align: center;
            padding: 40px;
            font-size: 18px;
            color: #2b6cb0;
        }
        .redirecting .spinner {
            display: inline-block;
            animation: spin 1s linear infinite;
            font-size: 40px;
        }
        @keyframes spin {
            0% { transform: rotate(0deg); }
            100% { transform: rotate(360deg); }
        }
        .redirect-link {
            color: #667eea;
            text-decoration: underline;
            font-weight: bold;
        }
        .redirect-link:hover {
            color: #5a67d8;
        }
    </style>
</head>
<body>
EOF

    if [ -n "$redirect_target" ]; then
        cat >> "$output_file" <<EOF
    <div class="container">
        <div class="redirecting">
            <div class="spinner">🔄</div>
            <h2>Redirecting to original page...</h2>
            <p>If you are not redirected automatically, <a href="$redirect_target" class="redirect-link">click here</a></p>
            <p style="font-size:14px;color:#a0aec0;margin-top:20px;">Original file: <code>$redirect_target</code></p>
        </div>
    </div>
    <script>
        window.location.href = '$redirect_target';
    </script>
</body>
</html>
EOF
        return
    fi

    cat >> "$output_file" <<EOF
    <div class="container">
        <div class="header">
            <h1>📁 $title</h1>
            <div class="path-info">📍 Location: $path_display</div>
        </div>
EOF

    if [ "$target_dir" != "$WEBROOT" ]; then
        echo '<a href=".." class="back-link">⬅ Back to Root</a>' >> "$output_file"
    fi
    
    echo '<div class="breadcrumb"><a href="/">Root</a>' >> "$output_file"
    
    if [ "$target_dir" != "$WEBROOT" ]; then
        local current_path=""
        local rel_path="${target_dir#$WEBROOT}"
        IFS='/' read -ra parts <<< "$rel_path"
        for part in "${parts[@]}"; do
            if [ -n "$part" ]; then
                current_path="$current_path/$part"
                echo " / <a href=\"$current_path\">$part</a>" >> "$output_file"
            fi
        done
    fi
    
    echo '</div>' >> "$output_file"
    
    cat >> "$output_file" <<'EOF'
        <div class="stats" id="stats">
            <div class="stat-item"><strong>📂 Items:</strong> <span id="itemCount">0</span></div>
            <div class="stat-item"><strong>📁 Directories:</strong> <span id="dirCount">0</span></div>
            <div class="stat-item"><strong>📄 Files:</strong> <span id="fileCount">0</span></div>
            <div class="stat-item"><strong>💾 Total Size:</strong> <span id="totalSize">N/A</span></div>
        </div>
        
        <input type="text" class="search-box" id="searchBox" placeholder="🔍 Search files... (Ctrl+/)">
        
        <div id="fileList" class="file-grid">
EOF

    local total_size=$(du -sh "$target_dir" 2>/dev/null | cut -f1)
    local dir_count=0
    local file_count=0
    
    declare -a dirs=()
    declare -a files=()
    
    for item in "$target_dir"/*; do
        [ -e "$item" ] || continue
        local name=$(basename "$item")
        
        if is_hidden "$name"; then
            continue
        fi
        
        if [ -d "$item" ]; then
            dirs+=("$name")
        else
            files+=("$name")
        fi
    done
    
    IFS=$'\n' dirs=($(sort <<<"${dirs[*]}"))
    IFS=$'\n' files=($(sort <<<"${files[*]}"))
    
    for name in "${dirs[@]}"; do
        ((dir_count++))
        local item="$target_dir/$name"
        local item_size=$(du -sh "$item" 2>/dev/null | cut -f1)
        echo "<a href=\"./$name/\" class=\"file-item\">" >> "$output_file"
        echo "  <span class=\"icon\">📁</span>" >> "$output_file"
        echo "  <span class=\"name\">$name/</span>" >> "$output_file"
        echo "  <span class=\"badge badge-dir\">DIR</span>" >> "$output_file"
        echo "  <span style=\"font-size:11px;color:#718096;\">$item_size</span>" >> "$output_file"
        echo "</a>" >> "$output_file"
    done
    
    for name in "${files[@]}"; do
        ((file_count++))
        local item="$target_dir/$name"
        local item_size=$(du -sh "$item" 2>/dev/null | cut -f1)
        local ext="${name##*.}"
        
        if [[ "$name" != *.* ]] || [ -z "$ext" ]; then
            ext="none"
        fi
        
        local icon=$(get_file_icon "$ext")
        local badge=$(get_file_badge "$ext")
        local badge_class="badge-text"
        
        case "$ext" in
            php) badge_class="badge-php" ;;
            html|htm) badge_class="badge-html" ;;
            css) badge_class="badge-css" ;;
            js) badge_class="badge-js" ;;
            jpg|jpeg|png|gif|svg|webp|ico) badge_class="badge-img" ;;
            pdf) badge_class="badge-pdf" ;;
            zip|tar|gz|rar|7z) badge_class="badge-zip" ;;
            sh|bash|zsh) badge_class="badge-sh" ;;
            py) badge_class="badge-py" ;;
            *) badge_class="badge-text" ;;
        esac
        
        echo "<a href=\"./$name\" class=\"file-item\">" >> "$output_file"
        echo "  <span class=\"icon\">$icon</span>" >> "$output_file"
        echo "  <span class=\"name\">$name</span>" >> "$output_file"
        echo "  <span class=\"badge $badge_class\">$badge</span>" >> "$output_file"
        echo "  <span style=\"font-size:11px;color:#718096;\">$item_size</span>" >> "$output_file"
        echo "</a>" >> "$output_file"
    done
    
    cat >> "$output_file" <<EOF
        </div>
        
        <div class="sort-info">📋 Sorted: Folders first, then files (alphabetically)</div>
        <div class="generated-info">🔄 Generated: $(date '+%Y-%m-%d %H:%M:%S')</div>
        
        <div class="footer">
            ⚡ Generated by Termux Apache Manager &bull; 
            $(date '+%A, %B %d, %Y at %H:%M:%S')
        </div>
    </div>
    
    <script>
        document.getElementById('itemCount').textContent = $((dir_count + file_count));
        document.getElementById('dirCount').textContent = $dir_count;
        document.getElementById('fileCount').textContent = $file_count;
        document.getElementById('totalSize').textContent = '$total_size';
        
        const searchBox = document.getElementById('searchBox');
        const fileItems = document.querySelectorAll('.file-item');
        
        searchBox.addEventListener('input', function(e) {
            const query = e.target.value.toLowerCase();
            let visible = 0;
            
            fileItems.forEach(item => {
                const name = item.querySelector('.name').textContent.toLowerCase();
                const isVisible = name.includes(query);
                item.style.display = isVisible ? 'flex' : 'none';
                if (isVisible) visible++;
            });
            
            document.getElementById('itemCount').textContent = visible;
        });
        
        document.addEventListener('keydown', function(e) {
            if (e.ctrlKey && e.key === '/') {
                e.preventDefault();
                searchBox.focus();
            }
        });
    </script>
</body>
</html>
EOF
}

# ===== GENERATE INDEX FOR A DIRECTORY =====
generate_directory_index() {
    local dir="$1"
    local force="${2:-false}"
    local dir_name=$(basename "$dir")
    local dir_path="/${dir#$WEBROOT/}"
    local index_file="$dir/index.html"
    
    if [ "$force" = "true" ]; then
        echo -e "${BLUE}[+] Generating: $dir_path${NC}"
    else
        if ! scan_changes "$dir"; then
            echo -e "${YELLOW}  ⏭ $dir_path unchanged${NC}"
            return 0
        fi
        echo -e "${BLUE}[+] Changes detected in $dir_path, regenerating...${NC}"
    fi
    
    rename_existing_index "$dir"
    generate_index_html "$dir" "$index_file" "$dir_name" "$dir_path"
    echo -e "${GREEN}✓ Directory index generated: $dir_path/index.html${NC}"
}

# ===== GENERATE FOR DIRECTORY AND ALL SUBDIRECTORIES =====
generate_with_subdirs() {
    local dir="$1"
    local force="${2:-false}"
    
    echo -e "${BLUE}[+] Generating indexes for: $dir and all subdirectories${NC}"
    
    # Generate for the main directory
    generate_directory_index "$dir" "$force"
    
    # Generate for all subdirectories
    find "$dir" -type d -not -path "$dir" | while read -r subdir; do
        # Skip backup directories
        local subdir_name=$(basename "$subdir")
        if [[ "$subdir_name" =~ _backup_[0-9]{8}_[0-9]{6}$ ]]; then
            continue
        fi
        generate_directory_index "$subdir" "$force"
    done
    
    echo -e "${GREEN}✓ All indexes generated for $dir and subdirectories${NC}"
}

# ===== SCAN FOR CHANGES =====
scan_changes() {
    local dir="$1"
    local dir_hash=$(echo "$dir" | md5sum | cut -d' ' -f1)
    local watch_file="$FILE_WATCH/${dir_hash}.watch"
    local current_snapshot=""
    
    current_snapshot=$(find "$dir" -maxdepth 1 -printf "%f %T@\n" 2>/dev/null | while read -r name timestamp; do
        if ! is_hidden "$name"; then
            echo "$name $timestamp"
        fi
    done | sort)
    
    if [ -f "$watch_file" ]; then
        local old_snapshot=$(cat "$watch_file")
        
        if [ "$current_snapshot" != "$old_snapshot" ]; then
            echo "$current_snapshot" > "$watch_file"
            return 0
        fi
        return 1
    else
        echo "$current_snapshot" > "$watch_file"
        return 0
    fi
}

# ===== GENERATE ALL FOLDER INDEXES (RECURSIVE) - FORCE =====
generate_all_indexes() {
    local force="${1:-false}"
    
    if [ "$force" != "true" ]; then
        echo -e "${YELLOW}⚠ Use 'force' to regenerate all indexes${NC}"
        return 0
    fi
    
    echo -e "${BLUE}[+] Force regenerating ALL folders (recursive)...${NC}"
    
    find "$WEBROOT" -type d | while read -r dir; do
        local dir_name=$(basename "$dir")
        if [[ "$dir_name" =~ _backup_[0-9]{8}_[0-9]{6}$ ]]; then
            continue
        fi
        generate_directory_index "$dir" "true"
    done
    
    echo -e "${GREEN}✓ All indexes generated${NC}"
}

# ===== FILE MANAGEMENT =====
copy_to_webroot() {
    local current_dir="$PWD"
    local dir_name="$(basename "$current_dir")"
    
    if [ "$dir_name" = "/" ]; then
        echo -e "${RED}✗ Cannot copy root directory${NC}"
        return 1
    fi
    
    if ! apache_running; then
        start_apache
    fi
    
    echo -e "${BLUE}[+] Copying '$dir_name' to webroot...${NC}"
    
    if [ -d "$WEBROOT/$dir_name" ]; then
        mv "$WEBROOT/$dir_name" "$WEBROOT/${dir_name}_backup_$(date +%Y%m%d_%H%M%S)"
    fi
    
    if cp -r "$current_dir" "$WEBROOT/"; then
        echo -e "${GREEN}✓ Copied successfully${NC}"
        
        # Generate indexes for the copied directory AND all subdirectories
        local new_dir="$WEBROOT/$dir_name"
        generate_with_subdirs "$new_dir" "true"
        
        # Also regenerate root index
        generate_directory_index "$WEBROOT" "true"
        
        local total_size=$(du -sh "$WEBROOT" 2>/dev/null | cut -f1)
        echo -e "\n${BLUE}📊 Webroot Info:${NC}"
        echo "  Location: $WEBROOT"
        echo "  Total Size: $total_size"
        echo "  Access at: $LOCAL_URL/$dir_name"
        
        read -p "Open in browser? (y/n): " -n 1 -r
        echo
        [[ $REPLY =~ ^[Yy]$ ]] && termux-open "$LOCAL_URL/$dir_name"
    else
        echo -e "${RED}✗ Failed to copy${NC}"
    fi
}

# ===== REMOVE MENU =====
remove_menu() {
    while true; do
        clear
        echo "=============================="
        echo "📂 File Removal Manager"
        echo "=============================="
        echo ""
        
        local items=()
        local count=1
        
        declare -a menu_dirs=()
        declare -a menu_files=()
        
        for item in "$WEBROOT"/*; do
            [ -e "$item" ] || continue
            name=$(basename "$item")
            
            if is_hidden "$name"; then
                continue
            fi
            
            if [ -d "$item" ]; then
                menu_dirs+=("$name")
            else
                menu_files+=("$name")
            fi
        done
        
        IFS=$'\n' menu_dirs=($(sort <<<"${menu_dirs[*]}"))
        IFS=$'\n' menu_files=($(sort <<<"${menu_files[*]}"))
        
        for name in "${menu_dirs[@]}"; do
            echo "$count) 📁 $name/"
            items[$count]="$WEBROOT/$name"
            ((count++))
        done
        
        for name in "${menu_files[@]}"; do
            echo "$count) 📄 $name"
            items[$count]="$WEBROOT/$name"
            ((count++))
        done
        
        if [ $count -eq 1 ]; then
            echo -e "${YELLOW}No items to remove${NC}"
            echo ""
            read -p "Press Enter to continue..."
            return 0
        fi
        
        echo ""
        echo "a) Remove ALL"
        echo "0) Exit"
        echo ""
        read -p "Select: " choice
        
        case "$choice" in
            0) break ;;
            a|A)
                echo ""
                read -p "Type 'yes' to confirm: " confirm
                if [ "$confirm" = "yes" ]; then
                    for item in "${items[@]}"; do
                        rm -rf "$item"
                        echo -e "${GREEN}✓ Removed: $(basename "$item")${NC}"
                    done
                    generate_directory_index "$WEBROOT" "true"
                fi
                sleep 2
                ;;
            *)
                if [ -n "${items[$choice]}" ]; then
                    local removed_item="${items[$choice]}"
                    rm -rf "$removed_item"
                    echo -e "${GREEN}✓ Removed: $(basename "$removed_item")${NC}"
                    generate_directory_index "$WEBROOT" "true"
                    sleep 1
                else
                    echo -e "${RED}✗ Invalid choice${NC}"
                    sleep 1
                fi
                ;;
        esac
    done
}

# ===== SHOW SIZE =====
show_size() {
    echo ""
    echo -e "${BLUE}📊 Webroot Size Information${NC}"
    echo "=============================="
    echo ""
    
    local webroot_size=$(du -sh "$WEBROOT" 2>/dev/null | cut -f1)
    local file_count=$(find "$WEBROOT" -type f 2>/dev/null | wc -l)
    local dir_count=$(find "$WEBROOT" -type d 2>/dev/null | wc -l)
    
    echo "📁 Directory: $WEBROOT"
    echo "💾 Total Size: $webroot_size"
    echo "📄 Total Files: $file_count"
    echo "📂 Total Directories: $dir_count"
    echo ""
    
    echo -e "${BLUE}📊 Root Directory Contents (Folders First):${NC}"
    
    du -sh "$WEBROOT"/* 2>/dev/null | sort -hr | while read size path; do
        name=$(basename "$path")
        if is_hidden "$name"; then
            continue
        fi
        if [ -d "$path" ]; then
            echo "  📁 $size → $name/"
        fi
    done
    
    du -sh "$WEBROOT"/* 2>/dev/null | sort -hr | while read size path; do
        name=$(basename "$path")
        if is_hidden "$name"; then
            continue
        fi
        if [ ! -d "$path" ]; then
            echo "  📄 $size → $name"
        fi
    done
    
    echo ""
    read -p "Press Enter to continue..."
}

# ===== EDIT INDEX =====
edit_index() {
    nano "$INDEX"
}

# ===== STATUS =====
show_status() {
    echo ""
    echo -e "${BLUE}📊 Server Status${NC}"
    echo "=============================="
    echo ""
    
    if apache_running; then
        echo -e "${GREEN}✓ Apache: RUNNING${NC}"
        echo "  PID: $(pgrep httpd | head -1)"
        echo "  Port: 8080"
    else
        echo -e "${RED}✗ Apache: STOPPED${NC}"
    fi
    
    echo ""
    
    if php_running; then
        echo -e "${GREEN}✓ PHP: RUNNING${NC}"
        echo "  PID: $(cat $PHP_PID 2>/dev/null)"
        echo "  Port: $PHP_PORT"
    else
        echo -e "${RED}✗ PHP: STOPPED${NC}"
    fi
    
    echo ""
    
    local webroot_size=$(du -sh "$WEBROOT" 2>/dev/null | cut -f1)
    local file_count=$(find "$WEBROOT" -type f 2>/dev/null | wc -l)
    local dir_count=$(find "$WEBROOT" -type d 2>/dev/null | wc -l)
    
    echo -e "${BLUE}📁 Webroot:${NC}"
    echo "  Path: $WEBROOT"
    echo "  Size: $webroot_size"
    echo "  Files: $file_count"
    echo "  Directories: $dir_count"
    echo ""
}

# ===== MAIN MENU =====
show_menu() {
    while true; do
        clear
        echo "=============================="
        echo "🚀 TERMUX APACHE MANAGER"
        echo "=============================="
        echo ""
        
        if apache_running; then
            echo -e "  ${GREEN}● Apache: RUNNING${NC}"
        else
            echo -e "  ${RED}● Apache: STOPPED${NC}"
        fi
        
        if php_running; then
            echo -e "  ${GREEN}● PHP: RUNNING${NC}"
        else
            echo -e "  ${RED}● PHP: STOPPED${NC}"
        fi
        
        echo ""
        echo "1) Start Apache"
        echo "2) Stop Apache (stops PHP too)"
        echo "3) Restart Apache"
        echo "4) Start PHP"
        echo "5) Stop PHP"
        echo "6) Restart PHP"
        echo "7) Show PHP Processes"
        echo "8) Kill PHP Processes"
        echo "9) Show Ports"
        echo "10) Status"
        echo "11) Copy current directory (with subdirs)"
        echo "12) Remove files"
        echo "13) Generate current directory + subdirs"
        echo "14) Force regenerate ALL indexes (recursive)"
        echo "15) Edit index.html"
        echo "16) Show webroot size"
        echo "17) Open in browser"
        echo "0) Exit"
        echo ""
        read -p "Select: " choice
        
        case $choice in
            1) start_apache ;;
            2) stop_apache ;;
            3) restart_apache ;;
            4) start_php ;;
            5) stop_php ;;
            6) restart_php ;;
            7) php_show_processes ;;
            8) php_kill_menu ;;
            9) show_ports ;;
            10) show_status ;;
            11) copy_to_webroot ;;
            12) remove_menu ;;
            13) generate_with_subdirs "$PWD" ;;
            14) generate_all_indexes "true" ;;
            15) edit_index ;;
            16) show_size ;;
            17) termux-open "$LOCAL_URL" ;;
            0) exit 0 ;;
            *) echo -e "${RED}✗ Invalid option${NC}"; sleep 1 ;;
        esac
        
        [ $choice -ne 0 ] && read -p "Press Enter to continue..."
    done
}

# ===== COMMAND LINE =====
if [ $# -eq 0 ]; then
    show_menu
else
    case "$1" in
        --help|-h|help) show_help ;;
        start) start_apache ;;
        stop) stop_apache ;;
        restart) restart_apache ;;
        php)
            case "$2" in
                start) start_php ;;
                stop) stop_php ;;
                restart) restart_php ;;
                ps) php_show_processes ;;
                kill) php_kill_menu ;;
                *) echo "Usage: $0 php start|stop|restart|ps|kill" ;;
            esac
            ;;
        ports) show_ports ;;
        copy) copy_to_webroot ;;
        remove) remove_menu ;;
        index) generate_with_subdirs "$PWD" ;;
        force) generate_all_indexes "true" ;;
        edit) edit_index ;;
        status) show_status ;;
        size) show_size ;;
        open) termux-open "$LOCAL_URL" ;;
        *) echo -e "${RED}✗ Unknown command: $1${NC}"
           echo ""
           echo -e "${YELLOW}Available commands:${NC}"
           echo "  start|stop|restart|status|copy|remove|index|force|edit|size|open|php|ports"
           echo ""
           echo -e "${YELLOW}PHP subcommands:${NC}"
           echo "  php start|stop|restart|ps|kill"
           echo ""
           echo -e "${YELLOW}For detailed help:${NC}"
           echo "  $0 --help"
           ;;
    esac
fi
