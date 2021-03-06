package com.cheatbreaker.obf;

import com.cheatbreaker.obf.transformer.Transformer;
import com.cheatbreaker.obf.transformer.flow.ExceptionTransformer;
import com.cheatbreaker.obf.transformer.general.StripTransformer;
import com.cheatbreaker.obf.transformer.methods.DynamicTransformer;
import com.cheatbreaker.obf.transformer.misc.ChecksumTransformer;
import com.cheatbreaker.obf.transformer.misc.PackerTransformer;
import com.cheatbreaker.obf.transformer.misc.VariableTransformer;
import com.cheatbreaker.obf.transformer.natives.CodeHiderTransformer;
import com.cheatbreaker.obf.transformer.natives.ConstantPoolTransformer;
import com.cheatbreaker.obf.transformer.strings.ToStringTransformer;
import com.cheatbreaker.obf.utils.asm.ClassWrapper;
import com.cheatbreaker.obf.utils.asm.ContextClassWriter;
import com.cheatbreaker.obf.utils.configuration.file.YamlConfiguration;
import com.cheatbreaker.obf.utils.loader.LoaderUtil;
import com.cheatbreaker.obf.utils.tree.ClassTree;
import lombok.Getter;
import org.apache.commons.io.IOUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import sun.util.calendar.BaseCalendar;

import java.io.*;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

public class Obf implements Opcodes {

    private final Map<String, ClassTree> hierachy = new HashMap<>();

    private Manifest manifest;

    @Getter
    private static Obf instance;

    private final ThreadLocalRandom random;
    private List<ClassWrapper> classes = new ArrayList<>();
    private final List<ClassWrapper> libs = new ArrayList<>(65525);
    private final List<Transformer> transformers = new ArrayList<>();
    private final YamlConfiguration config;
    private final HashMap<String, byte[]> resources = new HashMap<>();
    private final HashMap<String, byte[]> generated = new HashMap<>();

    @Getter
    private final LoaderUtil loader;

    public List<Transformer> getTransformers() {
        return transformers;
    }

    public Obf(YamlConfiguration configuration) throws Exception {

        this.config = configuration;
        this.loader = new LoaderUtil(Obf.class.getClassLoader());

        File inputFile = new File(config.getString("input"));
        File outputFile = new File(config.getString("output"));
        List<File> libs = config.getStringList("libs").stream().map(File::new).collect(Collectors.toList());

        instance = this;
        loadJavaRuntime();

        LinkedList<Thread> libraryThreads = new LinkedList<>();

        System.out.println("Loading libraries...");

        for (String library : libraries) {
            libraryThreads.add(new Thread(() -> {
                try {
                    loadJar(new File(library), true);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
        }

        for (File folder : libs) {
            for (File lib : walkFolder(folder)) {
                if (lib.getName().startsWith("rt")) {
                    continue;
                }
                libraryThreads.add(new Thread(() -> {
                    try {
                        loadJar(lib, true);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }));
            }
        }

        for (Thread libraryThread : libraryThreads) {
            libraryThread.start();
        }

        for (Thread libraryThread : libraryThreads) {
            libraryThread.join();
        }

        System.out.println("Reading jar...");

        loadJar(inputFile, false);

        random = ThreadLocalRandom.current();

        System.out.println("Loading transformers...");

        transformers.add(new StripTransformer(this));
//        transformers.add(new InlinerTransformer(this));
        transformers.add(new ExceptionTransformer(this));
        transformers.add(new VariableTransformer(this));
        transformers.add(new DynamicTransformer(this));
        transformers.add(new ToStringTransformer(this));
        transformers.add(new ChecksumTransformer(this));
        transformers.add(new ConstantPoolTransformer(this));
        transformers.add(new CodeHiderTransformer(this));
        transformers.add(new PackerTransformer(this));

        long start = System.currentTimeMillis();

        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(outputFile))) {

            System.out.println("Transforming classes...");

            for (Transformer transformer : transformers) {
                if (!transformer.enabled) continue;
                new ArrayList<>(classes).forEach(transformer::run);
            }

            for (Transformer transformer : transformers) {
                if (!transformer.enabled) continue;
                try {
                    transformer.getClass().getDeclaredMethod("after");
                } catch (NoSuchMethodException e) {
                    continue;
                }
                transformer.runAfter();
            }

            // Write manifest
            if (manifest != null) {
                ZipEntry e = new ZipEntry(JarFile.MANIFEST_NAME);
                out.putNextEntry(e);
                manifest.write(new BufferedOutputStream(out));
                out.closeEntry();
            }

            System.out.println("Writing classes...");

            for (ClassWrapper classNode : classes) {

                byte[] b = generated.getOrDefault(classNode.name, null);

                if (b != null && b.length == 0) continue;

                if (b == null) {
                    ContextClassWriter writer = new ContextClassWriter(ClassWriter.COMPUTE_FRAMES);
                    try {
                        classNode.accept(writer);
                        b = writer.toByteArray();
                    } catch (Exception ex) {
                        System.out.println("Failed to compute frames for class: " + classNode.name + ", " + ex.getMessage());
                        writer = new ContextClassWriter(ClassWriter.COMPUTE_MAXS);
                        classNode.accept(writer);
                        b = writer.toByteArray();
                    }
                }

                if (b != null) {
                    out.putNextEntry(new JarEntry(classNode.name + ".class"));
                    out.write(b);
                }
            }

            System.out.println("Writing resources...");
            resources.forEach((name, data) -> {
                try {
                    if (name.equals(JarFile.MANIFEST_NAME))
                        return;
                    out.putNextEntry(new JarEntry(name));
                    out.write(data);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });

            out.close();

            long difference = outputFile.length() - inputFile.length();

            boolean compressed = difference < 0;
            Date epoch = new Date(0);
            Date elapsed = Date.from(Instant.ofEpochMilli(System.currentTimeMillis() - start));

            StringBuilder time = new StringBuilder();

            int dh = elapsed.getHours() - epoch.getHours();
            int dm = elapsed.getMinutes() - epoch.getMinutes();
            int ds = elapsed.getSeconds() - epoch.getSeconds();

            if (dh > 0)
                time.append(dh).append("h ");
            if (dm > 0)
                time.append(dm).append("m ");
            if (ds > 0)
                time.append(ds).append("s ");
            Method normalize = Date.class.getDeclaredMethod("normalize");
            normalize.setAccessible(true);
            BaseCalendar.Date date = (BaseCalendar.Date) normalize.invoke(elapsed);
            time.append(date.getMillis()).append("ms");

            System.out.printf("Size: %.2fKB -> %.2fKB (%s%.2f%%)\n",
                    inputFile.length() / 1024D, outputFile.length() / 1024D, compressed ? "-" : "+", (100D * Math.abs((double) difference) / (double) inputFile.length()));
            System.out.printf("Elapsed: %s\n", time);
        }
    }

    public HashMap<String, byte[]> getResources() {
        return resources;
    }

    private final Vector<String> libraries = new Vector<>();

    public void loadJavaRuntime() {
        String path = System.getProperty("sun.boot.class.path");
        if (path != null) {
            String[] pathFiles = path.split(";");
            for (String lib : pathFiles) {
                if (lib.endsWith(".jar")) {
                    libraries.addElement(lib);
                }
            }
        }

    }

    public void loadJar(File inputFile, boolean lib) throws Exception {
        if (!inputFile.exists()) return;
        JarFile inputJar = new JarFile(inputFile);
        if (!lib) manifest = inputJar.getManifest();
        for (Enumeration<JarEntry> iter = inputJar.entries(); iter.hasMoreElements(); ) {
            JarEntry entry = iter.nextElement();
            if (entry.isDirectory()) continue;
            try (InputStream in = inputJar.getInputStream(entry)) {
                byte[] bytes = IOUtils.toByteArray(in);
                if (entry.getName().endsWith(".class") || entry.getName().endsWith(".class/")) {
                    ClassReader reader = new ClassReader(bytes);
                    ClassWrapper classNode = new ClassWrapper(!lib);
                    reader.accept(classNode, ClassReader.SKIP_FRAMES);
                    if (lib) libs.add(classNode);
                    else {
                        classes.add(classNode);
                    }
                    loader.addClass(classNode.name, bytes);
                } else {
                    if (!lib) resources.put(entry.getName(), bytes);
                }
            }
        }
    }

    private List<File> walkFolder(File folder) {
        List<File> files = new ArrayList<>();
        if (!folder.isDirectory()) {
            if (folder.getName().endsWith(".jar"))
                files.add(folder);
            return files;
        }
        for (File file : folder.listFiles()) {
            if (file.isDirectory()) {
                files.addAll(walkFolder(file));
            } else {
                if (file.getName().endsWith(".jar"))
                    files.add(file);
            }
        }
        return files;
    }

    public void loadHierachy() {
        Set<String> processed = new HashSet<>();
        LinkedList<ClassWrapper> toLoad = new LinkedList<>(this.classes);
        toLoad.addAll(libs);
        while (!toLoad.isEmpty()) {
            for (ClassWrapper toProcess : loadHierachy(toLoad.poll())) {
                if (processed.add(toProcess.name)) {
                    toLoad.add(toProcess);
                }
            }
        }
    }

    public ClassTree getClassTree(String classNode) {
        ClassTree tree = hierachy.get(classNode);
        if (tree == null) {
            loadHierachyAll(assureLoaded(classNode));
            return getClassTree(classNode);
        }
        return tree;
    }

    private ClassTree getOrCreateClassTree(String name) {
        return this.hierachy.computeIfAbsent(name, ClassTree::new);
    }

    public List<ClassWrapper> loadHierachy(ClassWrapper specificNode) {
        if (specificNode.name.equals("java/lang/Object")) {
            return Collections.emptyList();
        }
        List<ClassWrapper> toProcess = new ArrayList<>();

        ClassTree thisTree = getOrCreateClassTree(specificNode.name);
        ClassWrapper superClass;

        superClass = assureLoaded(specificNode.superName);

        if (superClass == null) {
            throw new IllegalArgumentException("Could not load " + specificNode.name);
        }

        ClassTree superTree = getOrCreateClassTree(superClass.name);
        superTree.subClasses.add(specificNode.name);
        thisTree.parentClasses.add(superClass.name);
        toProcess.add(superClass);

        for (String interfaceReference : specificNode.interfaces) {
            ClassWrapper interfaceNode = assureLoaded(interfaceReference);
            if (interfaceNode == null) {
                throw new IllegalArgumentException("Could not load " + interfaceReference);
            }
            ClassTree interfaceTree = getOrCreateClassTree(interfaceReference);
            interfaceTree.subClasses.add(specificNode.name);
            thisTree.parentClasses.add(interfaceReference);
            toProcess.add(interfaceNode);
        }
        return toProcess;
    }

    public void loadHierachyAll(ClassWrapper classNode) {
        Set<String> processed = new HashSet<>();
        LinkedList<ClassWrapper> toLoad = new LinkedList<>();
        toLoad.add(classNode);
        while (!toLoad.isEmpty()) {
            for (ClassWrapper toProcess : loadHierachy(toLoad.poll())) {
                if (processed.add(toProcess.name)) {
                    toLoad.add(toProcess);
                }
            }
        }
    }

    public YamlConfiguration getConfig() {
        return config;
    }

    public void addResource(String name, byte[] data) {
        resources.put(name, data);
    }


    public ThreadLocalRandom getRandom() {
        return random;
    }

    public void setClasses(List<ClassWrapper> classes) {
        this.classes = classes;
    }

    public List<ClassWrapper> getClasses() {
        return classes;
    }

    public List<ClassWrapper> getLibs() {
        return libs;
    }

    public void addClass(ClassWrapper classNode) {
        classes.add(classNode);
    }

    public ClassWrapper assureLoaded(String owner) {
        if (owner == null) return null;
        for (ClassWrapper classNode : classes) {
            if (classNode.name.equals(owner)) return classNode;
        }
        for (ClassWrapper classNode : libs) {
            if (classNode == null) continue;
            if (classNode.name.equals(owner)) return classNode;
        }
        return null;
//        throw new NoClassDefFoundError(owner);
    }

    public Manifest getManifest() {
        return manifest;
    }

    public void addGeneratedClass(String name, byte[] b) {
        generated.put(name, b);
    }

    public boolean isTransformerEnabled(Class<? extends Transformer> transformer) {
        return transformers.stream().anyMatch(t -> t.getClass().equals(transformer) && t.enabled);
    }
}
