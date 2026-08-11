package io.github.liumaishenjian.ccjava.cli.session;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/**
 * lock/fence、journal、staging、逐记录 verify、atomic publish 的 Session migration。
 *
 * <p>journal 在每个不可逆边界前后 force；重启先在同一 writer fence 内对账。VERIFIED staging
 * 可继续 publish，PUBLISHED target 经 digest 验证后只清理派生工件；其他不确定状态删除 staging
 * 并保留旧 canonical。任何时刻不会修改源 canonical，也不会把 staging 当作事实源。</p>
 *
 * @since 0.1.0
 */
public final class SessionMigrationCoordinator {
    static final int MAX_RECORDS = 1_000_000;
    static final int MAX_RECORD_CHARS = 1_048_576;
    static final long MAX_SOURCE_BYTES = 4L * 1024 * 1024 * 1024;
    private final FaultInjector faults;

    /** 创建不注入故障的生产迁移协调器。 */
    public SessionMigrationCoordinator() { this(point -> { }); }
    SessionMigrationCoordinator(FaultInjector faults) {
        this.faults = Objects.requireNonNull(faults, "faults 不能为空");
    }

    /** 将一条 canonical 源记录转换为单行目标记录。 */
    @FunctionalInterface public interface RecordMigrator {
        /**
         * 迁移一条 canonical Session record。
         *
         * @param canonicalLine 不含换行的源记录
         * @return 不含换行的目标记录
         * @throws Exception 记录无法安全迁移时
         */
        String migrate(String canonicalLine) throws Exception;
    }

    /**
     * 执行或恢复一次迁移；返回固定状态，不暴露路径或异常正文。
     *
     * @param source canonical 源普通文件
     * @param target create-only 目标文件
     * @param fromMajor 源 schema major
     * @param toMajor 目标 schema major，必须更大
     * @param migrator 单条 canonical record 迁移函数
     * @return 固定迁移终态与成功处理记录数
     */
    public MigrationResult migrate(Path source, Path target, int fromMajor, int toMajor,
            RecordMigrator migrator) {
        Objects.requireNonNull(source); Objects.requireNonNull(target); Objects.requireNonNull(migrator);
        if (fromMajor < 1 || toMajor <= fromMajor) return new MigrationResult(false, "INVALID_VERSION", 0);
        int records = 0;
        try {
            Path checkedSource = regularSource(source);
            Path parent = targetParent(target);
            Path checkedTarget = directChild(parent, target.toAbsolutePath().normalize().getFileName().toString());
            String base = checkedTarget.getFileName().toString();
            Path lockPath = directChild(parent, base + ".migration.lock");
            Path staged = directChild(parent, base + ".migration.staged");
            Path journal = directChild(parent, base + ".migration.journal");
            verifyAbsentOrRegular(checkedTarget); verifyAbsentOrRegular(lockPath);
            verifyAbsentOrRegular(staged); verifyAbsentOrRegular(journal);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock lock = channel.tryLock()) {
                if (lock == null) return new MigrationResult(false, "MIGRATION_ACTIVE", 0);
                Recovery recovery = recover(staged, checkedTarget, journal, parent);
                if (recovery.completed()) return new MigrationResult(true, "RECOVERED", recovery.records());
                if (Files.exists(checkedTarget, LinkOption.NOFOLLOW_LINKS)) {
                    return new MigrationResult(false, "TARGET_CONFLICT", 0);
                }
                faults.at(CrashPoint.AFTER_RECOVERY);
                safeDelete(staged); safeDelete(journal);
                writeJournal(journal, new Journal("PREPARED", fromMajor, toMajor, 0, "0".repeat(64)));
                faults.at(CrashPoint.AFTER_PREPARED);
                records = streamMigrate(checkedSource, staged, migrator);
                faults.at(CrashPoint.AFTER_STAGED);
                String digest = sha256(staged);
                int verifiedRecords = verifyRecords(staged);
                if (verifiedRecords != records) throw new MigrationFailure("VERIFY_FAILED");
                writeJournalReplace(journal, new Journal("VERIFIED", fromMajor, toMajor, records, digest));
                faults.at(CrashPoint.AFTER_VERIFIED);
                if (!digest.equals(sha256(staged))) throw new MigrationFailure("VERIFY_FAILED");
                publishAtomic(staged, checkedTarget);
                forceDirectory(parent);
                faults.at(CrashPoint.AFTER_PUBLISH);
                writeJournalReplace(journal, new Journal("PUBLISHED", fromMajor, toMajor, records, digest));
                faults.at(CrashPoint.AFTER_PUBLISHED_JOURNAL);
                safeDelete(journal);
                forceDirectory(parent);
                faults.at(CrashPoint.AFTER_CLEANUP);
                return new MigrationResult(true, "PUBLISHED", records);
            }
        } catch (java.nio.channels.OverlappingFileLockException active) {
            return new MigrationResult(false, "MIGRATION_ACTIVE", records);
        } catch (MigrationFailure failure) {
            return new MigrationResult(false, failure.code, records);
        } catch (Exception failure) {
            return new MigrationResult(false, "MIGRATION_FAILED", records);
        }
    }

    private static Recovery recover(Path staged, Path target, Path journal, Path parent) throws Exception {
        if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) safeDelete(staged);
            return Recovery.none();
        }
        Journal state = readJournal(journal);
        if ("VERIFIED".equals(state.state()) && Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)
                && state.digest().equals(sha256(staged)) && state.records() == verifyRecords(staged)) {
            publishAtomic(staged, target); forceDirectory(parent); safeDelete(journal);
            return new Recovery(true, state.records());
        }
        if (("PUBLISHED".equals(state.state()) || "VERIFIED".equals(state.state()))
                && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                && state.digest().equals(sha256(target))) {
            safeDelete(staged); safeDelete(journal); forceDirectory(parent);
            return new Recovery(true, state.records());
        }
        safeDelete(staged); safeDelete(journal); forceDirectory(parent);
        return Recovery.none();
    }

    private static int streamMigrate(Path source, Path staged, RecordMigrator migrator) throws Exception {
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8);
                BufferedWriter writer = Files.newBufferedWriter(staged, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (count >= MAX_RECORDS) throw new MigrationFailure("SOURCE_LIMIT");
                if (line.isBlank() || line.length() > MAX_RECORD_CHARS) throw new MigrationFailure("RECORD_INVALID");
                String migrated = migrator.migrate(line);
                if (migrated == null || migrated.isBlank() || migrated.length() > MAX_RECORD_CHARS
                        || migrated.indexOf('\n') >= 0 || migrated.indexOf('\r') >= 0)
                    throw new MigrationFailure("VERIFY_FAILED");
                writer.write(migrated); writer.write('\n'); count++;
            }
        }
        force(staged); return count;
    }

    private static int verifyRecords(Path file) throws IOException {
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.length() > MAX_RECORD_CHARS || count++ >= MAX_RECORDS)
                    throw new MigrationFailure("VERIFY_FAILED");
            }
        }
        return count;
    }

    private static Journal readJournal(Path journal) throws IOException {
        verifyAbsentOrRegular(journal);
        java.util.Map<String,String> values = new java.util.LinkedHashMap<>();
        for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
            int split = line.indexOf('='); if (split < 1 || values.putIfAbsent(line.substring(0, split), line.substring(split + 1)) != null)
                throw new MigrationFailure("JOURNAL_INVALID");
        }
        try { return new Journal(values.get("state"), Integer.parseInt(values.get("from")),
                Integer.parseInt(values.get("to")), Integer.parseInt(values.get("records")), values.get("digest")); }
        catch (RuntimeException invalid) { throw new MigrationFailure("JOURNAL_INVALID"); }
    }

    private static void writeJournal(Path journal, Journal value) throws IOException {
        Files.writeString(journal, value.text(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); force(journal);
    }
    private static void writeJournalReplace(Path journal, Journal value) throws IOException {
        Path tmp = journal.resolveSibling(journal.getFileName() + ".tmp"); safeDelete(tmp);
        Files.writeString(tmp, value.text(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); force(tmp);
        Files.move(tmp, journal, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static void publishAtomic(Path staged, Path target) throws IOException {
        /* 没有跨文件事务证明时绝不覆盖既有 target；冲突保留双方供人工判断。 */
        Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
    }
    private static Path regularSource(Path source) throws IOException {
        Path p=source.toAbsolutePath().normalize(); if(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>MAX_SOURCE_BYTES||!p.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(p))throw new MigrationFailure("SOURCE_INVALID"); return p;
    }
    private static Path targetParent(Path target)throws IOException{Path p=target.toAbsolutePath().normalize().getParent();if(p==null)throw new MigrationFailure("TARGET_INVALID");Files.createDirectories(p);if(Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)||!p.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(p))throw new MigrationFailure("TARGET_INVALID");return p;}
    private static Path directChild(Path parent,String name)throws IOException{Path p=parent.resolve(name).normalize();if(!parent.equals(p.getParent()))throw new MigrationFailure("TARGET_INVALID");return p;}
    private static void verifyAbsentOrRegular(Path p)throws IOException{if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)))throw new MigrationFailure("TARGET_INVALID");}
    private static void force(Path p)throws IOException{try(FileChannel c=FileChannel.open(p,StandardOpenOption.WRITE)){c.force(true);}}
    private static void forceDirectory(Path p){try(FileChannel c=FileChannel.open(p,StandardOpenOption.READ)){c.force(true);}catch(IOException ignored){}}
    private static String sha256(Path p)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(p)){byte[]b=new byte[65536];int n;while((n=in.read(b))>=0)if(n>0)d.update(b,0,n);}return HexFormat.of().formatHex(d.digest());}
    private static void safeDelete(Path p){try{if(!Files.isSymbolicLink(p))Files.deleteIfExists(p);}catch(IOException ignored){}}

    enum CrashPoint { AFTER_RECOVERY, AFTER_PREPARED, AFTER_STAGED, AFTER_VERIFIED, AFTER_PUBLISH, AFTER_PUBLISHED_JOURNAL, AFTER_CLEANUP }
    @FunctionalInterface interface FaultInjector { void at(CrashPoint point) throws IOException; }
    private record Journal(String state,int from,int to,int records,String digest){Journal{if(state==null||!SetHolder.STATES.contains(state)||from<1||to<=from||records<0||digest==null||!digest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();}String text(){return "state="+state+"\nfrom="+from+"\nto="+to+"\nrecords="+records+"\ndigest="+digest+"\n";}}
    private static final class SetHolder { static final java.util.Set<String> STATES=java.util.Set.of("PREPARED","VERIFIED","PUBLISHED"); }
    private record Recovery(boolean completed,int records){static Recovery none(){return new Recovery(false,0);}}
    /**
     * 隐私安全的迁移结果。
     *
     * @param success 是否已发布或恢复目标
     * @param status 固定状态码
     * @param records 已验证记录数
     */
    public record MigrationResult(boolean success,String status,int records){}
    private static final class MigrationFailure extends IOException{final String code;MigrationFailure(String code){this.code=code;}}
}
