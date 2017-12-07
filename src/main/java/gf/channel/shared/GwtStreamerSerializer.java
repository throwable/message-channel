package gf.channel.shared;

import com.googlecode.gwtstreamer.client.Streamer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Serializer based on gwt-streamer project
 * Created by akuranov on 30/09/2015.
 */
public class GwtStreamerSerializer implements MessageSerializer {
    @Override
    public @Nonnull String toString(@Nullable Object object) {
        return Streamer.get().toString(object);
    }

    @Override
    public @Nullable Object fromString(@Nonnull String text) {
        return Streamer.get().fromString(text);
    }
}
