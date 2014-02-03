package unifiedloganalyzer.analyze.path.strace;

import java.io.IOException;
import java.util.TreeMap;
import java.util.Map;
import java.util.regex.Pattern;
//import java.util.Stack;

import trskop.IAppendTo;
import trskop.ICallback;
import trskop.utils.AppendableLike;

import unifiedloganalyzer.IOutputMessage;
import unifiedloganalyzer.IParsedData;
import unifiedloganalyzer.ParsedData;
import unifiedloganalyzer.analyze.AAnalyzer;
import unifiedloganalyzer.analyze.path.PathCompoundMessage;
import unifiedloganalyzer.analyze.path.PathOutputMessage;
import unifiedloganalyzer.parse.strace.StraceProcessStatusChangedParsedData;
import unifiedloganalyzer.parse.strace.StraceSignalParsedData;
import unifiedloganalyzer.parse.strace.StraceSyscallParsedData;
import unifiedloganalyzer.utils.IHasPid;


/**
 * Analyze strace output to produce list of opened files.
 *
 * This class models process hierarchy as it existed during execution to
 * produce absolute paths instead of just stupidly printing path arguments to
 * open calls.
 *
 * @author Peter Trsko
 */
public class StracePathAnalyzer extends AAnalyzer
{
    // {{{ Process model //////////////////////////////////////////////////////

    private static class Process implements IHasPid, IAppendTo
    {
        public static final int NO_PID = -2;

        private int _pid = NO_PID;
        private int _parentPid = NO_PID;
        private String _executable = null; // TODO
        private String _workingDirectory = null;

        // TODO: Queue of calls waiting to be resumend.

        // {{{ Constructors ///////////////////////////////////////////////////

        public Process(int pid, int parentPid, String workingDirectory)
        {
            _pid = pid;
            _parentPid = parentPid;
            _workingDirectory = workingDirectory;
        }

        public Process(Integer pid, Integer parentPid, String workingDirectory)
        {
            this(pid.intValue(), parentPid.intValue(), workingDirectory);
        }

        public Process(int pid, String workingDirectory)
        {
            this(pid, NO_PID, workingDirectory);
        }

        public Process(Integer pid, String workingDirectory)
        {
            this(pid.intValue(), NO_PID, workingDirectory);
        }

        public Process(int pid)
        {
            this(pid, NO_PID, null);
        }

        public Process(Integer pid)
        {
            this(pid.intValue(), NO_PID, null);
        }

        // }}} Constructors ///////////////////////////////////////////////////

        // {{{ Getters and setters ////////////////////////////////////////////

        public int getPid()
        {
            return _pid;
        }

        public void setPid(int pid)
        {
            // TODO: Throw exception?
            ;
        }


        public int getParentPid()
        {
            return _parentPid;
        }

        public void setParentPid()
        {
            ;
        }

        
        public String getExecutable()
        {
            return _executable;
        }

        public void setExecutable(String executable)
        {
            _executable = executable;
        }


        public String getWorkingDirectory()
        {
            return _workingDirectory;
        }

        public void setWorkingDirectory(String workingDirectory)
        {
            _workingDirectory = workingDirectory;
        }

        // }}} Getters and setters ////////////////////////////////////////////

        public boolean hasPid()
        {
            return _pid >= 0;
        }

        public boolean hasParentPid()
        {
            return _pid >= 0;
        }

        public boolean hasWrokingDirectory()
        {
            return _workingDirectory != null;
        }

        public void appendTo(Appendable buff) throws IOException
        {
            (new AppendableLike(buff))
                .append("{ pid = ")
                .append(_pid)
                .append('\n')

                .append(", parentPid = ")
                .append(_parentPid)
                .append('\n')

                .append(", workingDirectory = ")
                .append(_workingDirectory)
                .append('\n')

                .append("}\n");
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof Process
                && _pid == ((Process)obj).getPid();
        }
    }

    // {{{ Process model //////////////////////////////////////////////////////

    // {{{ Configuration //////////////////////////////////////////////////////

    public static class Configuration
    {
        private boolean _preserveParsedData = false;

        public static Configuration theDefault()
        {
            return new Configuration();
        }

        public static Configuration preserveParsedData(Configuration config)
        {
            config._preserveParsedData = true;

            return config;
        }

        public static Configuration preserveParsedData()
        {
            return preserveParsedData(theDefault());
        }

        public boolean shouldPreserveParsedData()
        {
            return _preserveParsedData;
        }
    }

    // }}} Configuration //////////////////////////////////////////////////////

    // {{{ Private attributes /////////////////////////////////////////////////

    /**
     * Map process ID to its kept state.
     */
    private Map<Integer, Process> _processes = null;

    private Statistics _statistics = null;

    private Configuration _config = null;

    // }}} Private attributes /////////////////////////////////////////////////

    // {{{ Constructors ///////////////////////////////////////////////////////

    public StracePathAnalyzer(Configuration config)
    {
        _processes = new TreeMap<Integer, Process>();
        _statistics = new Statistics();
        _config = config == null ? Configuration.theDefault() : config;
    }

    public StracePathAnalyzer()
    {
        this(null);
    }

    // }}} Constructors ///////////////////////////////////////////////////////

    /**
     * Update statistics with specified event.
     *
     * @param event
     *   Event that occurred and statistics are kept for.
     */
    private void updateStatistics(Statistics.Event event)
    {
        _statistics.update(event);
    }

    /**
     * Ask Statistics if we are processing first syscall.
     */
    private boolean isFirstSyscall()
    {
        return _statistics.getSyscallCount() == 1;
    }

    /**
     * Take working directory and file path and produce absolute file path if
     * it wasn't already absolute.
     *
     * @param workingDirectory
     *   Working directory of the process that did something with specified
     *   file.
     * @param file
     *   File path.
     *
     * @return
     *   Absolute path to file if it was possible to determine it, or original
     *   relative path if workingDirectory was null.
     */
    private String resolvePath(String wd, String file)
    {
        if (wd == null)
        {
            updateStatistics(Statistics.Event.WORKING_DIRECTORY_MISS);
        }

        if (file.charAt(0) != '/' && wd != null)
        {
            return wd + "/" + file;
        }

        return file;
    }

    // {{{ Process model operations ///////////////////////////////////////////

    private boolean haveProcess(Integer pid)
    {
        return _processes.containsKey(pid);
    }

    private boolean haveProcess(int pid)
    {
        return haveProcess(new Integer(pid));
    }

    private Process addProcess(int pid)
    {
        return addProcess(Process.NO_PID, pid);
    }

    private Process addProcess(int parentPid, int pid)
    {
        Process parent = null;
        Process child = null;

        if (haveProcess(pid))
        {
            throw new IllegalArgumentException("pid = " + pid);
        }

        parent = getProcess(parentPid);

        if (parent == null)
        {
            parent = new Process(pid == -1 ? Process.NO_PID : pid, null);
            _processes.put(new Integer(pid), parent);
        }

        child = new Process(pid, parentPid, parent.getWorkingDirectory());
        child.setWorkingDirectory(parent.getWorkingDirectory());

        _processes.put(new Integer(pid), child);

        return child;
    }

    private boolean hasWorkingDirectory(int pid)
    {
        return getWorkingDirectory(pid) != null;
    }

    private boolean hasWorkingDirectory(Integer pid)
    {
        return getWorkingDirectory(pid) != null;
    }

    private String getWorkingDirectory(int pid)
    {
        return getWorkingDirectory(new Integer(pid));
    }

    private String getWorkingDirectory(Integer pid)
    {
        Process process = getProcess(pid);

        if (process == null)
        {
            return null;
        }

        return process.getWorkingDirectory();
    }

    private Process getProcess(int pid)
    {
        return getProcess(new Integer(pid));
    }

    private Process getProcess(Integer pid)
    {
        Process p = _processes.get(pid);

        if (p == null)
        {
            updateStatistics(Statistics.Event.GET_PROCESS_MISS);
        }

        return p;
    }

    private Process getOrAddProcess(int pid)
    {
        Process process = getProcess(pid);
        
        if (process == null)
        {
            process = addProcess(pid);
        }

        return process;
    }

    // }}} Process model operations ///////////////////////////////////////////

    // {{{ Syscall processing /////////////////////////////////////////////////

    private void processGetcwdAndChdirSyscalls(
        StraceSyscallParsedData.Flag flag,
        String path,
        int pid)
    {
        if (flag == StraceSyscallParsedData.Flag.RESUMED_CALL)
        {
            updateStatistics(Statistics.Event.IGNORED_RESUMED_SYSCALL);

            return;
        }

        if (path == null)
        {
            updateStatistics(Statistics.Event.UNREPORTED_PARSING_FAILURE);
        }

        getOrAddProcess(pid).setWorkingDirectory(path);
    }

    private void processExecSyscall(
        StraceSyscallParsedData.Flag flag,
        int pid,
        StraceSyscallParsedData parsedData)
    {
        if (flag == StraceSyscallParsedData.Flag.RESUMED_CALL)
        {
            updateStatistics(Statistics.Event.IGNORED_RESUMED_SYSCALL);

            return;
        }

        if (!hasWorkingDirectory(pid))
        {
            // Guess working directory from PWD environment variable.
            String workingDirectory = parsedData.getEnvVar("PWD");

            if (workingDirectory == null)
            {
                updateStatistics(Statistics.Event.NO_PWD_ENV_VAR);
            }

            getOrAddProcess(pid).setWorkingDirectory(workingDirectory);
        }

        getOrAddProcess(pid).setExecutable(parsedData.getPath());
    }

    private String processOpenSyscall(
        StraceSyscallParsedData.Flag flag,
        String file,
        int pid)
    {
        if (flag == StraceSyscallParsedData.Flag.RESUMED_CALL)
        {
            updateStatistics(Statistics.Event.IGNORED_SYSCALL);

            return null;
        }

        return resolvePath(getOrAddProcess(pid).getWorkingDirectory(), file);
    }

    private void analyzeSyscall(StraceSyscallParsedData parsedData)
    {
        StraceSyscallParsedData.Syscall syscall = parsedData.getSyscall();
        int pid = parsedData.getPid();
        int childPid = parsedData.getChildPid();
        String file = parsedData.getPath();
        StraceSyscallParsedData.Flag flag = parsedData.getFlag();

        Process process = null;

        // With first call comes first process and it doesn't matter if it
        if (isFirstSyscall())
        {
            addProcess(pid);
        }

        switch (syscall)
        {
            case FORK:
                updateStatistics(Statistics.Event.FORK_SYSCALL);
                addProcess(pid, childPid);
                break;

            case EXEC:
                updateStatistics(Statistics.Event.EXEC_SYSCALL);
                processExecSyscall(flag, pid, parsedData);
                break;

            case GETCWD:
                updateStatistics(Statistics.Event.GETCWD_SYSCALL);
                processGetcwdAndChdirSyscalls(flag, file, pid);
                break;

            case CHDIR:
                updateStatistics(Statistics.Event.CHDIR_SYSCALL);
                processGetcwdAndChdirSyscalls(flag, file, pid);
                break;

            case OPEN:
                updateStatistics(Statistics.Event.OPEN_SYSCALL);
                // Resolve absolute path if possible, returns null if we should
                // ignore the open() call.
                file = processOpenSyscall(flag, file, pid);
                if (file != null)
                {
                    runCallbacks(
                        _config.shouldPreserveParsedData()
                        ? new PathCompoundMessage(file, parsedData)
                        : new PathOutputMessage(file));
                }
                break;

            default:
                updateStatistics(Statistics.Event.IGNORED_SYSCALL);
        }
    }

    // }}} Syscall processing /////////////////////////////////////////////////

    // {{{ AAnalyzer, implementation of abstract methods //////////////////////

    protected void processEmptyMessage(IParsedData parsedData)
    {
        runCallbacks(_statistics);
    }

    protected void processParsedMessage(IParsedData parsedData)
    {
        if (parsedData instanceof StraceSyscallParsedData)
        {
            updateStatistics(Statistics.Event.SYSCALL);
            analyzeSyscall((StraceSyscallParsedData)parsedData);
        }
        else if (parsedData instanceof StraceSignalParsedData)
        {
            updateStatistics(Statistics.Event.SIGNAL);
        }
        else if (parsedData instanceof StraceProcessStatusChangedParsedData)
        {
            updateStatistics(Statistics.Event.PROCESS_STATUS_CHANGE);
        }
        else
        {
            updateStatistics(Statistics.Event.UNKNOWN_MESSAGE);
        }
    }

    protected void processParseError(IParsedData parsedData)
    {
        updateStatistics(Statistics.Event.PARSE_ERROR);
    }

    // }}} AAnalyzer, implementation of abstract methods //////////////////////
}