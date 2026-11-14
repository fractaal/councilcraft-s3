package memtweaks;

import com.google.common.collect.ForwardingMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * A view of a map whose entries only look up their value when it is read.
 *
 * With ModernFix the model bakery's map of model files is computed on demand: asking for a value parses that
 * file. Looping over its entries therefore parses every model file in the game, even for code that only looks
 * at the keys. More Axolotl Variants API does exactly that on every resource load (it is searching for files
 * named *_axolotl_bucket.json), which costs about four seconds in this pack. Through this view such a loop
 * costs nothing, and code that does read a value gets the same one as before.
 */
public final class LazyEntryMap<K, V> extends ForwardingMap<K, V> {
    private final Map<K, V> delegate;

    public LazyEntryMap(Map<K, V> delegate) {
        this.delegate = delegate;
    }

    @Override
    protected Map<K, V> delegate() {
        return delegate;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Entry<K, V>> iterator() {
                Iterator<K> keys = delegate.keySet().iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return keys.hasNext();
                    }

                    @Override
                    public Entry<K, V> next() {
                        K key = keys.next();
                        return new Entry<>() {
                            @Override
                            public K getKey() {
                                return key;
                            }

                            @Override
                            public V getValue() {
                                return delegate.get(key);
                            }

                            @Override
                            public V setValue(V value) {
                                throw new UnsupportedOperationException();
                            }
                        };
                    }
                };
            }

            @Override
            public int size() {
                return delegate.size();
            }
        };
    }
}
