package com.github.mustachejava.impl;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import com.github.mustachejava.Mustache;
import com.github.mustachejava.ObjectHandler;

/**
 * Lookup objects using reflection and execute them the same way.
 * <p/>
 * User: sam
 * Date: 7/24/11
 * Time: 3:02 PM
 */
public class DefaultObjectHandler implements ObjectHandler {

  // Create a map if one doesn't already exist -- MapMaker.computerHashMap seems to be
  // very inefficient, had to improvise
  protected static Map<Class, Map<String, MethodWrapper>> cache = new HashMap<Class, Map<String, MethodWrapper>>() {
    public synchronized Map<String, MethodWrapper> get(Object c) {
      Map<String, MethodWrapper> o = super.get(c);
      if (o == null) {
        o = new HashMap<String, MethodWrapper>();
        put((Class) c, o);
      }
      return o;
    }
  };

  private static final Method MAP_METHOD;
  private static final Method FUTURE_METHOD;
  static {
    try {
      MAP_METHOD = Map.class.getMethod("get", Object.class);
      FUTURE_METHOD = Future.class.getMethod("get");
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }
  
  private static Logger logger = Logger.getLogger(Mustache.class.getName());

  private static MethodWrapper nothing = new MethodWrapper(null, null);

  public MethodWrapper find(String name, Object... scopes) {
    MethodWrapper methodWrapper = null;
    int size = scopes.length;
    NEXT:
    for (int i = size - 1; i >= 0; i--) {
      Object scope = scopes[i];
      if (scope == null) continue;
      List<MethodWrapper> methodWrappers = null;
      int dotIndex;
      String subname = name;
      while ((dotIndex = subname.indexOf('.')) != -1) {
        String lookup = subname.substring(0, dotIndex);
        subname = subname.substring(dotIndex + 1);
        methodWrapper = findWrapper(scope, lookup);
        if (methodWrapper != null) {
          if (methodWrappers == null) methodWrappers = new ArrayList<MethodWrapper>();
          methodWrappers.add(methodWrapper);
          try {
            scope = methodWrapper.call(scope);
          } catch (MethodGuardException e) {
            throw new AssertionError(e);
          }
        } else {
          continue NEXT;
        }
      }
      MethodWrapper wrapper = findWrapper(scope, subname);
      if (wrapper != null) {
        methodWrapper = wrapper;
        wrapper.setScope(i);
        if (methodWrappers != null) {
          wrapper.addWrappers(methodWrappers.toArray(new MethodWrapper[methodWrappers.size()]));
        }
        break;
      }
    }
    return methodWrapper;
  }
  
  private MethodWrapper findWrapper(Object scope, String name) {
    if (scope == null) return null;
    if (scope instanceof Future) {
      try {
        scope = ((Future) scope).get();
      } catch (Exception e) {
        throw new RuntimeException("Failed to get value from future", e);
      }
    }
    if (scope instanceof Map) {
      Map map = (Map) scope;
      if (map.get(name) == null) {
        return null;
      } else {
        return new MethodWrapper(scope.getClass(), MAP_METHOD, name);
      }
    }
    Class aClass = scope.getClass();
    Map<String, MethodWrapper> members;
    // Don't overload methods in your contexts
    members = cache.get(aClass);
    MethodWrapper member;
    synchronized (members) {
      member = members.get(name);
    }
    if (member == nothing) return null;
    if (member == null) {
      try {
        member = getField(name, aClass);
        synchronized (members) {
          members.put(name, member);
        }
      } catch (NoSuchFieldException e) {
        // Not set
      }
    }
    if (member == null) {
      try {
        synchronized (members) {
          member = getMethod(name, aClass);
          members.put(name, member);
        }
      } catch (NoSuchMethodException e) {
        try {
          synchronized (members) {
            member = getMethod(name, aClass, List.class);
            members.put(name, member);
          }
        } catch (NoSuchMethodException e1) {
          String propertyname = name.substring(0, 1).toUpperCase() +
                  (name.length() > 1 ? name.substring(1) : "");
          try {
            synchronized (members) {
              member = getMethod("get" + propertyname, aClass);
              members.put(name, member);
            }
          } catch (NoSuchMethodException e2) {
            try {
              synchronized (members) {
                member = getMethod("is" + propertyname, aClass);
                members.put(name, member);
              }
            } catch (NoSuchMethodException e3) {
              // Nothing to be done
            }
          }
        }
      }
    }
    return member;
  }

  @Override
  public Iterator iterate(Object object) {
    Iterator i;
    if (object instanceof Iterator) {
      return (Iterator) object;
    } else if (object instanceof Iterable) {
      i = ((Iterable) object).iterator();
    } else {
      if (object == null) return EMPTY.iterator();
      if (object instanceof Boolean) {
        if (!(Boolean)object) {
          return EMPTY.iterator();
        }
      }
      if (object instanceof String) {
        if (object.toString().equals("")) {
          return EMPTY.iterator();
        }
      }
      i = new SingleValueIterator(object);
    }
    return i;
  }

  public static MethodWrapper getMethod(String name, Class aClass, Class... params) throws NoSuchMethodException {
    Method member;
    try {
      member = aClass.getDeclaredMethod(name, params);
    } catch (NoSuchMethodException nsme) {
      Class superclass = aClass.getSuperclass();
      if (superclass != Object.class) {
        return getMethod(name, superclass, params);
      }
      throw nsme;
    }
    if ((member.getModifiers() & Modifier.PRIVATE) == Modifier.PRIVATE) {
      throw new NoSuchMethodException("Only public, protected and package members allowed");
    }
    member.setAccessible(true);
    return new MethodWrapper(aClass, member);
  }

  public static MethodWrapper getField(String name, Class aClass) throws NoSuchFieldException {
    Field member;
    try {
      member = aClass.getDeclaredField(name);
    } catch (NoSuchFieldException nsfe) {
      Class superclass = aClass.getSuperclass();
      if (superclass != Object.class) {
        return getField(name, superclass);
      }
      throw nsfe;
    }
    if ((member.getModifiers() & Modifier.PRIVATE) == Modifier.PRIVATE) {
      throw new NoSuchFieldException("Only public, protected and package members allowed");
    }
    member.setAccessible(true);
    return new MethodWrapper(aClass, member);
  }

  protected static class SingleValueIterator implements Iterator {
    private boolean done;
    private Object value;

    public SingleValueIterator(Object value) {
      this.value = value;
    }

    @Override
    public boolean hasNext() {
      return !done;
    }

    @Override
    public Object next() {
      if (!done) {
        done = true;
        return value;
      }
      throw new NoSuchElementException();
    }

    @Override
    public void remove() {
      done = true;
    }
  }

}
