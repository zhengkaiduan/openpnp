package org.openpnp;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileReader;
import java.nio.file.FileSystems;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.swing.AbstractAction;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.openpnp.gui.MainFrame;
import org.openpnp.model.Configuration;
import org.openpnp.util.UiUtils;
import org.pmw.tinylog.Logger;

import com.google.common.io.Files;

import bsh.engine.BshScriptEngineFactory;

public class Scripting {
    JMenu menu;
    final ScriptEngineManager manager = new ScriptEngineManager();
    final String[] extensions;
    File scriptsDirectory;
    File eventsDirectory;
    WatchService watcher;

    public Scripting() {
        // Collect all the script filename extensions we know how to handle from the list of
        // available scripting engines.
        List<ScriptEngineFactory> factories = manager.getEngineFactories();
        Set<String> extensions = new HashSet<>();
        for (ScriptEngineFactory factory : factories) {
            for (String ext : factory.getExtensions()) {
                extensions.add(ext.toLowerCase());
            }
        }
        
        // Hack to fix BSH on Windows. See https://github.com/openpnp/openpnp/issues/462
        manager.registerEngineExtension("bsh", new BshScriptEngineFactory());
        manager.registerEngineExtension("java", new BshScriptEngineFactory());
        extensions.add("bsh");
        extensions.add("java");

        this.extensions = extensions.toArray(new String[] {});

        this.scriptsDirectory =
                new File(Configuration.get().getConfigurationDirectory(), "scripts");

        // Create the scripts directory if it doesn't exist and copy the example scripts
        // over.
        if (!getScriptsDirectory().exists()) {
            getScriptsDirectory().mkdirs();
        }
        
        // TODO: It would be better if we just copied all the files from the Examples
        // directory in the jar, but this is relatively difficult to do.
        // There is some information on how to do it in:
        // http://stackoverflow.com/questions/1386809/copy-directory-from-a-jar-file
        File examplesDir = new File(getScriptsDirectory(), "Examples");
        examplesDir.mkdirs();
        String[] exampleScripts =
                new String[] {
                        "JavaScript/Call_Java.js", 
                        "JavaScript/Hello_World.js", 
                        "JavaScript/Move_Machine.js", 
                        "JavaScript/Pipeline.js",
                        "JavaScript/Print_Scripting_Info.js",
                        "JavaScript/QrCodeXout.js",
                        "JavaScript/Reset_Strip_Feeders.js", 
                        "JavaScript/Utility.js", 
                        "Python/call_java.py", 
                        "Python/move_machine.py", 
                        "Python/print_hallo_openpnp.py",
                        "Python/print_methods_vars.py", 
                        "Python/print_nozzle_info.py", 
                        "Python/print_scripting_info.py",
                        "Python/use_module.py", 
                        "Python/utility.py"
                        };
        for (String name : exampleScripts) {
            try {
                File file = new File(examplesDir, name);
                if (file.exists()) {
                    continue;
                }
                FileUtils.copyURLToFile(ClassLoader.getSystemResource("scripts/Examples/" + name),
                        file);
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }

        this.eventsDirectory = new File(scriptsDirectory, "Events");
        if (!eventsDirectory.exists()) {
            eventsDirectory.mkdirs();
        }

        // Add a file watcher so that we can be notified if any scripts change
        try {
            watcher = FileSystems.getDefault().newWatchService();
            watchDirectory(getScriptsDirectory());
            Thread thread = new Thread(() -> {
                for (;;) {
                    try {
                        // wait for an event
                        WatchKey key = watcher.take();
                        key.pollEvents();
                        key.reset();
                        // rescan
                        synchronizeMenu(menu, getScriptsDirectory());
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setMenu(JMenu menu) {
        this.menu = menu;
        // Add a separator and the Refresh Scripts and Open Scripts Directory items
        menu.addSeparator();
        menu.add(new AbstractAction("Refresh Scripts") {
            {
                putValue(MNEMONIC_KEY, KeyEvent.VK_R);
            }

            @Override
            public void actionPerformed(ActionEvent e) {
                synchronizeMenu(menu, getScriptsDirectory());
            }
        });
        menu.add(new AbstractAction("Open Scripts Directory") {
            {
                putValue(MNEMONIC_KEY, KeyEvent.VK_O);
            }

            @Override
            public void actionPerformed(ActionEvent e) {
                UiUtils.messageBoxOnException(() -> {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(getScriptsDirectory());
                    }
                });
            }
        });

        // Synchronize the menu
        synchronizeMenu(menu, getScriptsDirectory());
    }

    public File getScriptsDirectory() {
        return scriptsDirectory;
    }

    private void watchDirectory(File directory) {
        try {
            directory.toPath().register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private synchronized void synchronizeMenu(JMenu menu, File directory) {
        if (menu == null) {
            return;
        }
        // Remove any menu items that don't have a matching entry in the directory
        Set<String> filenames = new HashSet<>(Arrays.asList(directory.list()));
        for (JMenuItem item : getScriptMenuItems(menu)) {
            if (!filenames.contains(item.getText())) {
                menu.remove(item);
            }
        }

        // Add any scripts not already in the menu
        Set<String> itemNames = getScriptMenuItems(menu).stream().map(JMenuItem::getText)
                .collect(Collectors.toSet());
        for (File script : FileUtils.listFiles(directory, extensions, false)) {
            if (!script.isFile()) {
                continue;
            }
            if (itemNames.contains(script.getName())) {
                continue;
            }
            JMenuItem item = new JMenuItem(script.getName());
            item.addActionListener((e) -> {
                UiUtils.messageBoxOnException(() -> execute(script));
            });
            addSorted(menu, item);
        }

        // And add any directories not already in the menu
        itemNames = getScriptMenuItems(menu).stream().map(JMenuItem::getText)
                .collect(Collectors.toSet());
        for (File d : directory.listFiles(File::isDirectory)) {
            if (d.equals(eventsDirectory)) {
                continue;
            }
            if (new File(d, ".ignore").exists()) {
                continue;
            }
            if (!itemNames.contains(d.getName())) {
                JMenu m = new JMenu(d.getName());
                addSorted(menu, m);
                watchDirectory(d);
            }
        }

        // Synchronize all of the sub-menus with their directories
        for (JMenuItem item : getScriptMenuItems(menu)) {
            if (item instanceof JMenu) {
                synchronizeMenu((JMenu) item, new File(directory, item.getText()));
            }
        }
    }

    private void addSorted(JMenu menu, JMenuItem item) {
        if (menu.getItemCount() == 0) {
            menu.add(item);
            return;
        }
        for (int i = 0; i < menu.getItemCount(); i++) {
            JMenuItem existingItem = menu.getItem(i);
            if (existingItem == null || item.getText().toLowerCase()
                    .compareTo(existingItem.getText().toLowerCase()) <= 0) {
                menu.insert(item, i);
                return;
            }
        }
        menu.add(item);
    }

    private List<JMenuItem> getScriptMenuItems(JMenu menu) {
        List<JMenuItem> items = new ArrayList<>();
        for (int i = 0; i < menu.getItemCount(); i++) {
            // Once we hit the separator we stop
            if (menu.getItem(i) == null) {
                break;
            }
            items.add(menu.getItem(i));
        }
        return items;
    }

    public void execute(String script) throws Exception {
        execute(new File(script), null);
    }
    
    public void execute(File script) throws Exception {
        execute(script, null);
    }
    
    public void execute(String script, Map<String, Object> additionalGlobals) throws Exception {
      execute(new File(script), additionalGlobals );
    }
    
    public void execute(File script, Map<String, Object> additionalGlobals) throws Exception {
        ScriptEngine engine =
                manager.getEngineByExtension(Files.getFileExtension(script.getName()));

        engine.put("config", Configuration.get());
        engine.put("machine", Configuration.get().getMachine());
        engine.put("gui", MainFrame.get());
        engine.put("scripting", this);
        engine.put(ScriptEngine.FILENAME, script.getName());

        if (additionalGlobals != null) {
            for (String name : additionalGlobals.keySet()) {
                engine.put(name, additionalGlobals.get(name));
            }
        }

        try (FileReader reader = new FileReader(script)) {
            engine.eval(reader);
        }
    }

    public void on(String event, Map<String, Object> globals) throws Exception {
        Logger.trace("Scripting.on " + event);
        for (File script : FileUtils.listFiles(eventsDirectory, extensions, false)) {
            if (!script.isFile()) {
                continue;
            }
            if (FilenameUtils.getBaseName(script.getName()).equals(event)) {
                Logger.trace("Scripting.on found " + script.getName());
                execute(script, globals);
            }
        }
    }
}
