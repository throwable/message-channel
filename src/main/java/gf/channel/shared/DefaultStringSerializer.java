package gf.channel.shared;

import com.google.gwt.http.client.URL;
import com.googlecode.gwtstreamer.client.impl.StreamerInternal;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Default implementation of serializer.
 *
 * Created by akuranov on 30/09/2015.
 */
public class DefaultStringSerializer implements MessageSerializer {
    @Override
    public @Nonnull String toString(@Nullable Object object) {
        String str = object != null ? object.toString() : "";
        return StreamerInternal.urlEncode(str);
    }

    @Override
    public @Nullable Object fromString(@Nonnull String text) {
        return StreamerInternal.urlDecode(text);
    }
}
