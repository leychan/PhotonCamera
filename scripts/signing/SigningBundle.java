import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;

class SigningBundle {
    private static final int MAX_SIZE = 8 * 1024 * 1024;
    private static final String STORE_PASSWORD = "RELEASE_STORE_PASSWORD";
    private static final String KEY_PASSWORD = "RELEASE_KEY_PASSWORD";
    private static final String KEY_ALIAS = "RELEASE_KEY_ALIAS";
    private final Path root;
    private final Path local;
    private final Properties messages = new Properties();

    SigningBundle(Path root) throws IOException {
        this.root = root.toRealPath();
        local = this.root.resolve(".signing");
        loadMessages("messages.properties");
        if (Locale.getDefault().getLanguage().equals("zh")) {
            loadMessages("messages_zh.properties");
        }
    }

    private void loadMessages(String name) throws IOException {
        try (var reader = Files.newBufferedReader(root.resolve("scripts/signing/" + name))) {
            messages.load(reader);
        }
    }

    private String message(String key, Object... args) {
        return String.format(messages.getProperty(key), args);
    }

    private void require(boolean condition, String key) {
        if (!condition) throw new IllegalArgumentException(message(key));
    }

    private byte[] readLimited(java.io.InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_SIZE + 1);
        require(bytes.length <= MAX_SIZE, "tooLarge");
        return bytes;
    }

    private byte[] readFile(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return readLimited(input);
        }
    }

    private Properties decode(byte[] bytes) throws IOException {
        var properties = new Properties();
        properties.load(new ByteArrayInputStream(bytes));
        return properties;
    }

    private byte[] encode(Properties properties) throws IOException {
        var output = new ByteArrayOutputStream();
        properties.store(output, null);
        return output.toByteArray();
    }

    private Path temporaryFile(String suffix) throws IOException {
        return Files.createTempFile(local, "material-", suffix,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }

    private KeyStore validate(byte[] bytes, Properties properties) throws Exception {
        for (String name : new String[]{STORE_PASSWORD, KEY_PASSWORD, KEY_ALIAS}) {
            require(properties.containsKey(name), "invalidBundle");
        }
        char[] storePassword = properties.getProperty(STORE_PASSWORD).toCharArray();
        char[] keyPassword = properties.getProperty(KEY_PASSWORD).toCharArray();
        try {
            // 按文件头选择格式，不依赖不同 JDK 的兼容模式开关。
            boolean jks = bytes.length >= 4 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xed
                    && bytes[2] == (byte) 0xfe && bytes[3] == (byte) 0xed;
            var store = KeyStore.getInstance(jks ? "JKS" : "PKCS12");
            store.load(new ByteArrayInputStream(bytes), storePassword);
            String alias = properties.getProperty(KEY_ALIAS);
            require(!alias.isEmpty() && store.getKey(alias, keyPassword) instanceof PrivateKey
                    && store.getCertificateChain(alias) != null, "invalidKey");
            return store;
        } catch (java.security.GeneralSecurityException | IOException error) {
            throw new IllegalArgumentException(message("invalidKey"));
        } finally {
            Arrays.fill(storePassword, '\0');
            Arrays.fill(keyPassword, '\0');
        }
    }

    private void fingerprint(KeyStore store, Properties properties) throws Exception {
        byte[] certificate = store.getCertificate(properties.getProperty(KEY_ALIAS)).getEncoded();
        System.out.println(message("fingerprint",
                HexFormat.ofDelimiter(":").withUpperCase().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(certificate))));
    }

    private void pack(Path keystore, String alias, boolean force) throws Exception {
        Path bundle = root.resolve("config/signing.age");
        require(force || !Files.exists(bundle), "bundleExists");
        Console console = System.console();
        require(console != null, "terminalRequired");
        var properties = new Properties();
        char[] storePassword = console.readPassword("%s", message("storePassword"));
        require(storePassword != null, "cancelled");
        char[] keyPassword = null;
        try {
            keyPassword = console.readPassword("%s", message("keyPassword"));
            require(keyPassword != null, "cancelled");
            properties.setProperty(STORE_PASSWORD, new String(storePassword));
            properties.setProperty(KEY_PASSWORD, new String(keyPassword.length == 0 ? storePassword : keyPassword));
            properties.setProperty(KEY_ALIAS, alias);
        } finally {
            Arrays.fill(storePassword, '\0');
            if (keyPassword != null) Arrays.fill(keyPassword, '\0');
        }
        byte[] bytes = readFile(keystore);
        encrypt(bytes, properties, force, null);
    }

    private void encrypt(byte[] bytes, Properties properties, boolean force, String passphrase) throws Exception {
        Path bundle = root.resolve("config/signing.age");
        require(force || !Files.exists(bundle), "bundleExists");
        var store = validate(bytes, properties);
        fingerprint(store, properties);
        properties.setProperty("version", "1");
        properties.setProperty("keystore", Base64.getEncoder().encodeToString(bytes));
        byte[] plaintext = encode(properties);
        require(plaintext.length <= MAX_SIZE, "tooLarge");
        Path encrypted = temporaryFile(".age");
        try {
            var process = ageProcess(true, passphrase)
                    .redirectError(ProcessBuilder.Redirect.INHERIT).redirectOutput(encrypted.toFile()).start();
            try {
                try (var input = process.getOutputStream()) {
                    input.write(plaintext);
                }
                require(process.waitFor() == 0, "ageFailed");
            } finally {
                process.destroy();
            }
            Files.createDirectories(bundle.getParent());
            Files.move(encrypted, bundle, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            System.out.println(message("packed", bundle));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(bytes, (byte) 0);
            Files.deleteIfExists(encrypted);
        }
    }

    private ProcessBuilder ageProcess(boolean encrypt, String passphrase) {
        var command = new ArrayList<String>();
        command.add("age");
        command.add(encrypt ? "--encrypt" : "--decrypt");
        if (passphrase != null) {
            require(!passphrase.isEmpty(), "passphraseMissing");
            require(Arrays.stream(System.getenv("PATH").split(java.io.File.pathSeparator))
                    .anyMatch(path -> Files.isExecutable(Path.of(path, "age-plugin-batchpass"))), "pluginMissing");
            command.add("-j");
            command.add("batchpass");
        } else if (encrypt) {
            command.add("--passphrase");
        }
        if (!encrypt) command.add(root.resolve("config/signing.age").toString());
        var builder = new ProcessBuilder(command);
        // 仅把解密口令传给官方插件，避免进入命令参数和构建进程。
        builder.environment().remove("RELEASE_SIGNING_PASSPHRASE");
        builder.environment().remove("AGE_PASSPHRASE");
        builder.environment().remove("AGE_PASSPHRASE_FD");
        if (passphrase != null) builder.environment().put("AGE_PASSPHRASE", passphrase);
        return builder;
    }

    private String randomPassword() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private void initialize(String alias) throws Exception {
        Path bundle = root.resolve("config/signing.age");
        Path masterFile = local.resolve("master-passphrase");
        require(!Files.exists(bundle) && !Files.exists(masterFile)
                && !Files.exists(local.resolve("release.properties")), "alreadyInitialized");
        String passphrase = randomPassword();
        // 先检查插件，依赖缺失时不创建任何正式密钥材料。
        ageProcess(true, passphrase);
        String password = randomPassword();
        var properties = new Properties();
        properties.setProperty(STORE_PASSWORD, password);
        properties.setProperty(KEY_PASSWORD, password);
        properties.setProperty(KEY_ALIAS, alias);
        Path keystore = temporaryFile(".p12");
        Files.delete(keystore);
        try {
            var builder = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair", "-keystore", keystore.toString(), "-storetype", "PKCS12",
                    "-alias", alias, "-keyalg", "RSA", "-keysize", "4096", "-sigalg", "SHA256withRSA",
                    "-validity", "36500", "-dname", "CN=PhotonCamera Release,O=PhotonCamera",
                    "-storepass:env", "PHOTON_KEYSTORE_PASSWORD", "-keypass:env", "PHOTON_KEYSTORE_PASSWORD",
                    "-noprompt").redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT);
            builder.environment().put("PHOTON_KEYSTORE_PASSWORD", password);
            var process = builder.start();
            try {
                process.getOutputStream().close();
                require(process.waitFor() == 0, "keyGenerationFailed");
            } finally {
                process.destroy();
            }
            Files.writeString(Files.createFile(masterFile,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))),
                    passphrase + "\n");
            encrypt(readFile(keystore), properties, false, passphrase);
            setup(passphrase);
            System.out.println(message("initialized", masterFile));
        } finally {
            Files.deleteIfExists(keystore);
            if (!Files.exists(bundle)) Files.deleteIfExists(masterFile);
        }
    }

    private void setup(String passphrase) throws Exception {
        Path bundle = root.resolve("config/signing.age");
        require(Files.isRegularFile(bundle), "bundleMissing");
        var process = ageProcess(false, passphrase)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        byte[] plaintext;
        try {
            process.getOutputStream().close();
            plaintext = readLimited(process.getInputStream());
            require(process.waitFor() == 0, "ageFailed");
        } finally {
            process.destroy();
        }
        Properties properties;
        try {
            properties = decode(plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
        require("1".equals(properties.getProperty("version")) && properties.containsKey("keystore"), "invalidBundle");
        byte[] bytes = Base64.getDecoder().decode(properties.getProperty("keystore"));
        var store = validate(bytes, properties);
        if ("true".equals(System.getenv("GITHUB_ACTIONS"))) {
            for (String name : new String[]{STORE_PASSWORD, KEY_PASSWORD}) {
                String secret = properties.getProperty(name).replace("%", "%25")
                        .replace("\r", "%0D").replace("\n", "%0A");
                System.out.println("::add-mask::" + secret);
            }
        }
        fingerprint(store, properties);
        var config = new Properties();
        for (String name : new String[]{STORE_PASSWORD, KEY_PASSWORD, KEY_ALIAS}) {
            config.setProperty(name, properties.getProperty(name));
        }
        config.setProperty("RELEASE_STORE_TYPE", store.getType());
        Path active = local.resolve("release.properties");
        Path oldKey = null;
        if (Files.exists(active)) {
            String previous = decode(readFile(active)).getProperty("RELEASE_STORE_FILE");
            if (previous != null) {
                Path candidate = root.resolve(previous).normalize();
                if (local.equals(candidate.getParent())
                        && candidate.getFileName().toString().matches("material-[0-9]+\\.keystore")) {
                    oldKey = candidate;
                }
            }
        }
        Path key = temporaryFile(".keystore");
        Path pending = temporaryFile(".properties");
        boolean installed = false;
        try {
            Files.write(key, bytes);
            config.setProperty("RELEASE_STORE_FILE", root.relativize(key).toString().replace('\\', '/'));
            Files.write(pending, encode(config));
            // 密钥先就位，最后原子切换配置，失败时仍可继续使用旧配置。
            Files.move(pending, active, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            installed = true;
            if (oldKey != null) Files.deleteIfExists(oldKey);
            System.out.println(message("configured", active));
        } finally {
            Arrays.fill(bytes, (byte) 0);
            Files.deleteIfExists(pending);
            if (!installed) Files.deleteIfExists(key);
        }
    }

    private void run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help")) {
            System.out.println(message("usage"));
            return;
        }
        boolean pack = args[0].equals("pack");
        boolean initialize = args[0].equals("init");
        require(pack || initialize || args[0].equals("setup"), "invalidArguments");
        Path keystore = null;
        Path passphraseFile = null;
        String alias = initialize ? "photon-release" : null;
        boolean force = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--force")) {
                require(pack, "invalidArguments");
                force = true;
            } else {
                require(i + 1 < args.length, "invalidArguments");
                switch (args[i++]) {
                    case "--keystore" -> {
                        require(pack, "invalidArguments");
                        keystore = Path.of(args[i]).toAbsolutePath();
                    }
                    case "--alias" -> {
                        require(pack || initialize, "invalidArguments");
                        alias = args[i];
                    }
                    case "--passphrase-file" -> {
                        require(!pack && !initialize, "invalidArguments");
                        passphraseFile = Path.of(args[i]);
                    }
                    default -> throw new IllegalArgumentException(message("invalidArguments"));
                }
            }
        }
        require(!pack || (keystore != null && alias != null), "invalidArguments");
        require(alias == null || !alias.isBlank(), "invalidArguments");
        String passphrase = System.getenv("RELEASE_SIGNING_PASSPHRASE");
        require(passphraseFile == null || passphrase == null, "conflictingPassphrases");
        if (passphraseFile != null) {
            passphrase = new String(readFile(passphraseFile), java.nio.charset.StandardCharsets.UTF_8)
                    .replaceFirst("[\\r\\n]+$", "");
        }
        require(passphrase == null || !passphrase.isEmpty(), "passphraseMissing");
        require(pack || initialize || !"true".equals(System.getenv("CI")) || passphrase != null, "passphraseMissing");
        try {
            var age = new ProcessBuilder("age", "--version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            require(age.waitFor() == 0, "ageMissing");
        } catch (IOException error) {
            throw new IllegalArgumentException(message("ageMissing"));
        }
        Files.createDirectories(local,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        require(Files.isDirectory(local, LinkOption.NOFOLLOW_LINKS), "invalidLocalDirectory");
        Files.setPosixFilePermissions(local, PosixFilePermissions.fromString("rwx------"));
        try (var channel = FileChannel.open(local.resolve("setup.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            require(lock != null, "busy");
            if (initialize) initialize(alias);
            else if (pack) pack(keystore, alias, force);
            else setup(passphrase);
        }
    }

    public static void main(String[] args) throws Exception {
        var tool = new SigningBundle(Path.of(args[0]));
        try {
            tool.run(Arrays.copyOfRange(args, 1, args.length));
        } catch (Exception error) {
            // 不打印异常栈和配置内容，避免密码进入终端日志。
            System.err.println(tool.message("error", error instanceof IllegalArgumentException
                    ? error.getMessage() : tool.message("operationFailed", error.getClass().getSimpleName())));
            System.exit(1);
        }
    }
}
