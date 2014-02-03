package unifiedloganalyzer.analyze.path;

import java.io.IOException;

import unifiedloganalyzer.IParsedData;
import unifiedloganalyzer.utils.CompoundMessage;
import unifiedloganalyzer.utils.IHasPath;


/**
 * Compound of PathCompoundMessage and some IParsedData instance.
 *
 * Message that contains file path result on one hand and its origin in, parsed
 * and unparsed form, on the other.
 *
 * @author Peter Trsko
 */
public class PathCompoundMessage
    extends CompoundMessage<PathOutputMessage, IParsedData>
    implements IHasPath
{
    // {{{ Constructors ///////////////////////////////////////////////////////

    public PathCompoundMessage(String path, IParsedData parsedData)
    {
        super(new PathOutputMessage(path), parsedData);
    }

    public PathCompoundMessage(IParsedData parsedData)
    {
        super(PathOutputMessage.empty(), parsedData);
    }

    public static PathCompoundMessage empty()
    {
        return new PathCompoundMessage(null);
    }

    // }}} Constructors ///////////////////////////////////////////////////////

    // {{{ IHasPath and IParsedData interface implementation //////////////////

    /**
     * {@inheritDoc}
     */
    public String getPath()
    {
        return getOutputMessage().getPath();
    }

    /**
     * {@inheritDoc}
     */
    public void setPath(String path)
    {
        getOutputMessage().setPath(path);
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasPath()
    {
        return getOutputMessage().hasPath();
    }

    // }}} IHasPath and IParsedData interface implementation //////////////////
}