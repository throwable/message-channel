package gf.channel.shared;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Serializer converts an object to a text message that may be transferred
 * by underlying transport. The string representation must not to use \n symbol
 * as it used by the underlying protocol.
 *
 * Created by anton on 25/09/2015.
 */
public interface MessageSerializer {
    @Nonnull String toString(@Nullable Object object);
    @Nullable Object fromString(@Nonnull String text);
}
