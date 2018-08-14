package com.oslorde.luadroid;

import android.annotation.TargetApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.dx.AnnotationId;
import com.android.dx.Code;
import com.android.dx.DexMaker;
import com.android.dx.FieldId;
import com.android.dx.Local;
import com.android.dx.MethodId;
import com.android.dx.TypeId;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.reflect.Modifier.PUBLIC;

@SuppressWarnings("unchecked")
public class ClassBuilder {
    private static final Map<TypeId<?>, TypeId<?>> PRIMITIVE_TO_BOXED;
    private static final Map<TypeId<?>, MethodId<?, ?>> PRIMITIVE_TYPE_TO_BOX_METHOD;
    /**
     * Map from primitive type to method used to unbox a boxed version of the primitive.
     * <p>
     * This is required for methods whose return type is primitive, since the
     * {@link InvocationHandler} will return us a boxed result, and we'll need to convert it back to
     * primitive value.
     */
    private static final Map<TypeId<?>, MethodId<?, ?>> PRIMITIVE_TO_UNBOX_METHOD;
    private static final String FUNC_REFS = "$_funcRefs_";

    static {
        PRIMITIVE_TO_BOXED = new HashMap<>();
        PRIMITIVE_TO_BOXED.put(TypeId.BOOLEAN, TypeId.get(Boolean.class));
        PRIMITIVE_TO_BOXED.put(TypeId.INT, TypeId.get(Integer.class));
        PRIMITIVE_TO_BOXED.put(TypeId.BYTE, TypeId.get(Byte.class));
        PRIMITIVE_TO_BOXED.put(TypeId.LONG, TypeId.get(Long.class));
        PRIMITIVE_TO_BOXED.put(TypeId.SHORT, TypeId.get(Short.class));
        PRIMITIVE_TO_BOXED.put(TypeId.FLOAT, TypeId.get(Float.class));
        PRIMITIVE_TO_BOXED.put(TypeId.DOUBLE, TypeId.get(Double.class));
        PRIMITIVE_TO_BOXED.put(TypeId.CHAR, TypeId.get(Character.class));
    }

    static {
        PRIMITIVE_TYPE_TO_BOX_METHOD = new HashMap<>();
        for (Map.Entry<TypeId<?>, TypeId<?>> entry : PRIMITIVE_TO_BOXED.entrySet()) {
            TypeId<?> primitiveType = entry.getKey();
            TypeId<?> boxedType = entry.getValue();
            MethodId<?, ?> valueOfMethod = boxedType.getMethod(boxedType, "valueOf", primitiveType);
            PRIMITIVE_TYPE_TO_BOX_METHOD.put(primitiveType, valueOfMethod);
        }
    }

    static {
        Map<TypeId<?>, MethodId<?, ?>> map = new HashMap<>();
        map.put(TypeId.BOOLEAN, TypeId.get(Boolean.class).getMethod(TypeId.BOOLEAN, "booleanValue"));
        map.put(TypeId.INT, TypeId.get(Integer.class).getMethod(TypeId.INT, "intValue"));
        map.put(TypeId.BYTE, TypeId.get(Byte.class).getMethod(TypeId.BYTE, "byteValue"));
        map.put(TypeId.LONG, TypeId.get(Long.class).getMethod(TypeId.LONG, "longValue"));
        map.put(TypeId.SHORT, TypeId.get(Short.class).getMethod(TypeId.SHORT, "shortValue"));
        map.put(TypeId.FLOAT, TypeId.get(Float.class).getMethod(TypeId.FLOAT, "floatValue"));
        map.put(TypeId.DOUBLE, TypeId.get(Double.class).getMethod(TypeId.DOUBLE, "doubleValue"));
        map.put(TypeId.CHAR, TypeId.get(Character.class).getMethod(TypeId.CHAR, "charValue"));
        PRIMITIVE_TO_UNBOX_METHOD = map;
    }

    private DexMaker maker;
    private TypeId<?> type;
    private Class superType;
    private boolean isInterface;
    private ArrayList<ScriptContext.Func> funcRefs=new ArrayList<>();

    private ClassBuilder(DexMaker maker, TypeId<?> type,Class superType,boolean isInterface) {
        this.maker = maker;
        this.type = type;
        this.superType=superType;
        this.isInterface=isInterface;
    }

    private static <T> T mapGet(String key, int index, Map map) {
        T t = (T) map.get(index);
        if (t == null) t = (T) map.get(key);
        return t;
    }

    /**
     * @param typeInfo a map contains args named args in{@link ClassBuilder#declare(String, int, Class, Object[])}
     * @return a builder to constructor a class
     */
    public static ClassBuilder declare(Map typeInfo) {
        String name = mapGet("name", 1, typeInfo);
        Number flag = mapGet("flag", 2, typeInfo);
        Class<?> superType = mapGet("super", 3, typeInfo);
        Map<?, Class> map = mapGet("interfaces", 4, typeInfo);
        Object[] interfaces = null;
        if (map != null) interfaces = map.values().toArray(new Class[0]);
        return declare(name, flag.intValue(), superType, interfaces);
    }

    /**
     * declare a public class extending Object
     * @see ClassBuilder#declare(String, int, Class, Object[])
     */
    public static ClassBuilder declare(String name){
        return declare(name,Modifier.PUBLIC,null);
    }
    /**
     * declare a public class without interface
     * @see ClassBuilder#declare(String, int, Class, Object[])
     */
    public static ClassBuilder declare(String name, Class<?> superType){
        return declare(name,Modifier.PUBLIC,superType);
    }
    /**
     * declare a class extending Object
     * @see ClassBuilder#declare(String, int, Class, Object[])
     */
    public static ClassBuilder declare(String name, int flag){
        return declare(name,flag,null);
    }

    /**
     * declare a class without interface
     * @see ClassBuilder#declare(String, int, Class, Object[])
     */
    public static ClassBuilder declare(String name, int flag, Class<?> superType){
        return declare(name,flag,superType,new Object[0]);
    }
    /**
     * declare a public class
     * @see ClassBuilder#declare(String, int, Class, Object[])
     */
    public static ClassBuilder declare(String name,  Class<?> superType,Object... interfaces){
        return declare(name,Modifier.PUBLIC,superType,interfaces);
    }
    /**
     * declare a public class extending Object
     * @see ClassBuilder#declare(String, int, Class, Object[])
     */
    public static ClassBuilder declare(String name, Object... interfaces){
        return declare(name,Modifier.PUBLIC,null,interfaces);
    }

    /**
     * @param name class name,can be null
     * @param flag class modifiers,{@link Modifier}
     * @param superType class to extend,null is treated as Object.class
     *                  if the class is interface and no interfaces
     *                  then the class is the only interface.
     *                  otherwise,interface is not allowed as super class
     * @param interfaces interfaces to implement,nullable for lua call
     * @return a builder to constructor a class
     */
    public static ClassBuilder declare(String name, int flag, Class<?> superType, Object... interfaces) {
        if (name == null) name = generateClassName();
        if (name.length() == 1 && isPrimitiveType(name))
            throw new LuaException("Primitive type can't be declared");
        if (name.charAt(0) == '[')
            throw new LuaException("Array type can't be declared");
        name = fixName(name);
        DexMaker maker = new DexMaker();
        TypeId[] inters = getTypeIds(interfaces);
        if(inters.length==0&&superType.isInterface()){
            inters=new TypeId[]{TypeId.get(superType)};
            superType=null;
        }
        TypeId<Object> type = TypeId.get(name);
        maker.declare(type, "", flag, superType==null?TypeId.OBJECT:TypeId.get(superType), inters);
        return new ClassBuilder(maker, type,superType==null?Object.class:superType,Modifier.isInterface(flag));
    }

    private static String fixName(String name) {
        if (!validateName(name)) throw new LuaException("Invalidate Type Name:" + name);
        if (name.charAt(0) != 'L' || name.charAt(name.length()) != ';')
            name = 'L' + name + ';';
        name = name.replace('.', '/');
        return name;
    }

    private static TypeId[] getTypeIds(Object[] interfaces) {
        int len = interfaces == null ? 0 : interfaces.length;
        TypeId[] inters = new TypeId[len];
        for (int i = 0; i < len; ++i) {
            Object cl = interfaces[i];
            TypeId inter = getTypeId(cl);
            inters[i] = inter;
        }
        return inters;
    }

    private static TypeId<?> getTypeId(Object cl) {
        TypeId inter;
        if (cl instanceof Class) inter = TypeId.get((Class<?>) cl);
        else if (cl instanceof String) {
            inter = TypeId.get(fixName(cl.toString()));
        } else throw new LuaException("Illegal type:" + cl);
        return inter;
    }

    private static boolean isPrimitiveType(String s) {
        char c = s.charAt(0);
        return c == 'I' || c == 'J' || c == 'B' || c == 'S' || c == 'F' || c == 'D' || c == 'C' || c == 'V';
    }

    private static boolean validateName(String name) {
        if (name.length() == 1) {
            return isPrimitiveType(name);
        }
        if (name.charAt(0) == 'L' && name.charAt(name.length()) == ';')
            name = name.substring(1, name.length() - 1);
        name = name.replace('.', '/');
        String[] names = name.split("/");
        for (String shortName :
                names) {
            if (name.isEmpty()) return false;
            int c = name.codePointAt(0);
            if (!Character.isJavaIdentifierStart(c))
                return false;
            for (int i = 1, len = name.codePointCount(0, name.length()); i < len; i++) {
                if (!Character.isJavaIdentifierPart(name.codePointAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String generateClassName() {
        return "Lgenerated" + (int) (Math.random() * 1000) + ";";
    }

    private static void generateCodeForReturnStatement(Code code, TypeId<?> methodReturnType,
                                                       Local<?> localForResultOfInvoke,Local aBoxedResult,
                                                       Local localOfMethodReturnType) {
        if (PRIMITIVE_TO_BOXED.containsKey(methodReturnType)) {
            code.cast(aBoxedResult, localForResultOfInvoke);
            MethodId<Object,?> unboxingMethodFor = (MethodId<Object, ?>) getUnboxMethodForPrimitive(methodReturnType);
            code.invokeVirtual(unboxingMethodFor, localOfMethodReturnType, aBoxedResult);
            code.returnValue(localOfMethodReturnType);
        } else if (TypeId.VOID.equals(methodReturnType)) {
            code.returnVoid();
        } else {
            code.cast(localOfMethodReturnType, localForResultOfInvoke);
            code.returnValue(localOfMethodReturnType);
        }
    }

    private static MethodId<?, ?> getUnboxMethodForPrimitive(TypeId<?> methodReturnType) {
        return PRIMITIVE_TO_UNBOX_METHOD.get(methodReturnType);
    }

    private static Local<?> boxIfRequired(Code code, Local<?> parameter, Local<Object> temp) {
        MethodId<?, ?> unboxMethod = PRIMITIVE_TYPE_TO_BOX_METHOD.get(parameter.getType());
        if (unboxMethod == null) {
            return parameter;
        }
        code.invokeStatic(unboxMethod, temp, parameter);
        return temp;
    }

    private static String qualifyName(String name) {
        name = name.substring(1, name.length() - 1);
        name = name.replace('/', '.');
        return name;
    }
    /**
     * @param methodInfo a map contains args mapped by order or name
     * @see ClassBuilder#addMethod(String, int, Object, Object[], LuaFunction, List, List)
     */
    public ClassBuilder addMethod(Map methodInfo) {
        LuaFunction function = mapGet("func", 4, methodInfo);
        Map<?, Object> paramTypes = mapGet("paramTypes", 5, methodInfo);
        Map<?, Map<Object, Object>> annotations = mapGet("annotations", 6, methodInfo);
        Map<?, Map<?, Map<Object, Object>>> paramAnnotations = mapGet("paramAnnotations", 7, methodInfo);
        Integer flag = mapGet("flag", 2, methodInfo);
        if(flag==null)flag=Modifier.PUBLIC|(function==null?Modifier.ABSTRACT:0);
        return addMethod(mapGet("name", 1, methodInfo), flag,
                mapGet("returnType", 3, methodInfo)
                , paramTypes == null ? null : paramTypes.values().toArray(),
                function,
                annotations == null ? null : new ArrayList<>(annotations.values())
                , paramAnnotations == null ? null : new ArrayList<>(paramAnnotations.values()));
    }
    /**
     * add public method with implementation
     * @throws IllegalArgumentException if no implementation provided
     * @see ClassBuilder#addMethod(String, int, Object, Object[], LuaFunction, List, List)
     */
    public ClassBuilder addMethod(String name,  Object returnType,
                                  Object[] paramTypes, LuaFunction func){
        if(func==null) throw new IllegalArgumentException("Function must no be null");
        return addMethod(name,Modifier.PUBLIC,returnType,paramTypes,func);
    }
    /**
     * add a public method which has implementation, no param and return void
     * @see ClassBuilder#addMethod(String, Object, Object[], LuaFunction)
     */
    public ClassBuilder addMethod(String name,LuaFunction function){
        return addMethod(name,void.class,null,function);
    }
    /**
     * add public method with implementation and no param
     * @see ClassBuilder#addMethod(String, Object, Object[], LuaFunction)
     */
    public ClassBuilder addMethod(String name, Object returnType,LuaFunction function){
        return addMethod(name,returnType,null,function);
    }
    /**
     * add a public abstract method which has no param and return void
     * @see ClassBuilder#addMethod(String, int, Object, Object[], LuaFunction, List, List)
     */
    public ClassBuilder addMethod(String name){
        return addMethod(name,Modifier.PUBLIC,void.class);
    }
    /**
     * add a public abstract method with no param
     * @see ClassBuilder#addMethod(String, int, Object, Object[], LuaFunction, List, List)
     */
    public ClassBuilder addMethod(String name, Object returnType){
        return addMethod(name,Modifier.PUBLIC,returnType);
    }

    /**
     * add an abstract method with no param
     * @see ClassBuilder#addMethod(String, int, Object, Object[], LuaFunction, List, List)
     */
    public ClassBuilder addMethod(String name, int flag, Object returnType){
        return addMethod(name,flag|Modifier.ABSTRACT,returnType,null);
    }

    /**
     * @see ClassBuilder#addMethod(String, int, Object, Object[], LuaFunction, List, List)
     */
    public ClassBuilder addMethod(String name, int flag, Object returnType,
                          Object[] paramTypes){
        return addMethod(name,flag,returnType,paramTypes,null);
    }
    /**
     * @see ClassBuilder#addMethod(String, int, Object, Object[], LuaFunction, List, List)
     */
    public ClassBuilder addMethod(String name, int flag, Object returnType,
                          Object[] paramTypes, LuaFunction func){
        return addMethod(name,flag,returnType,paramTypes,func,null,null);
    }

    /**
     * @see ClassBuilder#addMethod(String, int, Object, Object[], LuaFunction, List, List)
     */
    public ClassBuilder addMethod(String name, int flag, Object returnType,
                          Object[] paramTypes, LuaFunction func, List<Map<Object, Object>> annotations){
        return addMethod(name,flag,returnType,paramTypes,func,annotations,null);
    }

    /**
     * @param name method name.
     * @param flag method modifiers,{@link Modifier}.
     * @param returnType method return type.
     * @param paramTypes parameter types,nullable if no arg.
     * @param func function to implement the lua function,auto wrap,nullable for abstract methods
     * @param annotations method annotations,nullable, represented by an array of table
     *                    with its first element as annotation class the left
     *                    as key and value. If the annotation has the second value only,
     *                    it's treated as "value".All annotations is set to runtime type.
     * @param paramAnnotations parameter annotations,represented by an array of annotations,nullable.
     */
    public ClassBuilder addMethod(String name, int flag, Object returnType,
                          Object[] paramTypes, LuaFunction func, List<Map<Object, Object>> annotations,
                          List<Map<?, Map<Object, Object>>> paramAnnotations) {
        if (Modifier.isStatic(flag))
            throw new IllegalArgumentException("Static method is not supported");
        TypeId[] argTypes = getTypeIds(paramTypes);
        if(name.equals("<init>"))
            throw new UnsupportedOperationException("constructor is not supported");
        if (paramAnnotations!=null&&paramAnnotations.size() > argTypes.length)
            throw new LuaException("Too many param annotations");
        TypeId<?> typeReturn = getTypeId(returnType);
        MethodId<?, ?> id = type.getMethod(typeReturn, name, argTypes);
        if (!Modifier.isAbstract(flag)&&func == null)
            throw new IllegalArgumentException("No implementation for " + id);
        Code code = maker.declare(id, flag);
        if (annotations != null) {
            AnnotationId.addToMethod(maker, id, getAnnotationIds(annotations, ElementType.METHOD));
        }
        if (paramAnnotations != null&&paramAnnotations.size()>0) {
            List[] ids = new List[argTypes.length];
            for (int i = paramAnnotations.size() - 1; i >= 0; --i) {
                Map<?, Map<Object, Object>> annotationsList = paramAnnotations.get(i);
                if (annotationsList == null) continue;
                ids[i] = getAnnotationIds(new ArrayList<>(annotationsList.values()), ElementType.PARAMETER);
            }
            AnnotationId.addToParameters(maker, id, Arrays.asList(ids));
        }
        if (code != null) {
            int pos = funcRefs.size();
            funcRefs.add(ScriptContext.getFunc(func, argTypes));
            generateCode(code,pos,typeReturn,argTypes);
        }
        return this;
    }

    private void generateCode(Code code, int pos, TypeId returnType, TypeId<?>[] argTypes) {
        FieldId funcRefs = type.getField(TypeId.get(ScriptContext.Func[].class), FUNC_REFS);
        Local<Integer> v0 = code.newLocal(TypeId.INT);
        Local<Object> v1 = code.newLocal(TypeId.get(Object.class));
        Local<Object[]> v2 = code.newLocal(TypeId.get(Object[].class));
        Local<Object> temp = code.newLocal(TypeId.OBJECT);
        TypeId<ScriptContext.Func> funcTypeId = TypeId.get(ScriptContext.Func.class);
        Local<ScriptContext.Func> v4 = code.newLocal(funcTypeId);
        TypeId<?> boxType = PRIMITIVE_TO_BOXED.get(returnType);
        Local<?> aBoxedResult=null;
        if(boxType!=null)
            aBoxedResult = code.newLocal(boxType);
        Local<Object> localOfMethodReturnType = (Local<Object>) code.newLocal(returnType);
        Local<?> thiz = code.getThis(this.type);
        code.loadConstant(v0, pos);
        code.iget(funcRefs, v1, thiz);
        code.aget(v1, v1, v0);
        code.loadConstant(v0, argTypes.length);
        code.newArray(v2, v0);
        for (int p = 0; p < argTypes.length; ++p) {
            code.loadConstant(v0, p);
            Local<?> parameter = code.getParameter(p, argTypes[p]);
            Local<?> unboxedIfNecessary = boxIfRequired(code, parameter, temp);
            code.aput(v2, v0, unboxedIfNecessary);
        }
        MethodId<ScriptContext.Func, Object> methodId = funcTypeId.getMethod(TypeId.OBJECT, "call", TypeId.OBJECT,
                TypeId.get(Object[].class));
        code.cast(v4, v1);
        code.invokeVirtual(methodId, v1, v4, thiz, v2);
        generateCodeForReturnStatement(code, returnType, v1,aBoxedResult,localOfMethodReturnType);
    }

    private List<AnnotationId<?, ?>> getAnnotationIds(List<Map<Object, Object>> annotations, ElementType elementType) {
        List<AnnotationId<?, ?>> list = new ArrayList<>();
        for (Map<Object, Object> map :
                annotations) {
            AnnotationId annotationId = AnnotationId.get(type, getTypeId(mapGet("type", 1, map)), elementType);
            if (map.remove(1) == null)
                map.remove("type");
            if (map.get(2) != null) {
                map.put("value", map.remove(2));
            }
            for (Map.Entry entry :
                    map.entrySet()) {
                annotationId.set(new AnnotationId.Element((String) entry.getKey(), entry.getValue()));
            }
            list.add(annotationId);
        }
        return list;
    }

    /**
     * add a public field
     * @see ClassBuilder#addField(String, int, Object, Object, List)
     */
    public ClassBuilder addField(String name, Object fieldType) {
        return addField(name, Modifier.PUBLIC, fieldType, null, null);
    }
    /**
     * add a public static field
     * @see ClassBuilder#addField(String, int, Object, Object, List)
     */
    public ClassBuilder addStaticField(String name,  Object fieldType) {
        return addField(name, Modifier.PUBLIC|Modifier.STATIC, fieldType, null, null);
    }
    /**
     * @see ClassBuilder#addField(String, int, Object, Object, List)
     */
    public ClassBuilder addField(String name, int flag, Object fieldType) {
        return addField(name, flag, fieldType, null, null);
    }

    /**
     * @see ClassBuilder#addField(String, int, Object, Object, List)
     */
    public ClassBuilder addField(String name, int flag, Object fieldType, Object staticValue) {
        return addField(name, flag, fieldType, staticValue, null);
    }

    /**
     *add a field to this class, static or not.
     * @param name field name.
     * @param flag field modifiers,{@link Modifier}.
     * @param fieldType field type
     * @param staticValue value can be set as static,such as enum,class,string,number
     * @param annotations field annotations,for more see {@link ClassBuilder#addMethod(String, int, Object, Object[], LuaFunction, List, List)}
     */
    public ClassBuilder addField(String name, int flag, Object fieldType, Object staticValue, List<Map<Object, Object>> annotations) {
        FieldId id = type.getField(getTypeId(fieldType), name);
        maker.declare(id, flag, staticValue);
        if (annotations == null) return this;
        for (Map<Object, Object> map :
                annotations) {
            AnnotationId.addToField(maker, id, getAnnotationIds(annotations, ElementType.FIELD));
        }
        return this;
    }
    /**
     * @param fieldInfo a map contains args mapped by order or name
     * @see ClassBuilder#addField(String, int, Object, Object, List)
     */
    public ClassBuilder addField(Map fieldInfo) {
        Map<?, Map<Object, Object>> annotations = mapGet("annotations", 5, fieldInfo);
        Integer flag = mapGet("flag", 2, fieldInfo);
        if(flag==null)flag=Modifier.PUBLIC;
        return addField(mapGet("name", 1, fieldInfo), flag, mapGet("type", 3, fieldInfo)
                , mapGet("value", 4, fieldInfo),
                annotations == null ? null : new ArrayList<>(annotations.values()));
    }

    /**
     * @return the class to build
     * @throws IOException if failed to generate class
     * @throws ClassNotFoundException if failed to load class
     */

    public Class build() throws IOException, ClassNotFoundException {
        addField(FUNC_REFS, Modifier.PRIVATE, ScriptContext.Func[].class);
        if(!isInterface){
            for (Constructor<?> constructor : getConstructorsToOverwrite(superType)) {
                if (constructor.getModifiers() == Modifier.FINAL) {
                    continue;
                }
                TypeId<?>[] types = getTypeIds(constructor.getParameterTypes());
                MethodId<?, ?> method = type.getConstructor(types);
                Code constructorCode = maker.declare(method, PUBLIC);
                Local<?> thisRef = constructorCode.getThis(type);
                Local<?>[] params = new Local[types.length];
                for (int i = 0; i < params.length; ++i) {
                    params[i] = constructorCode.getParameter(i, types[i]);
                }
                MethodId superConstructor = TypeId.get(superType).getConstructor(types);
                constructorCode.invokeDirect(superConstructor, null, thisRef, params);
                constructorCode.returnVoid();
            }
        }
        ClassLoader loader = maker.generateAndLoad(getClass().getClassLoader(), null);
        return loader.loadClass(qualifyName(type.getName()));
    }
    private static <T> Constructor<T>[] getConstructorsToOverwrite(Class<T> clazz) {
        return (Constructor<T>[]) clazz.getDeclaredConstructors();
    }


    /**
     *set handlers for methods
     * @param ob instance of generated type
     * @throws NoSuchFieldException if it's not a generated type
     * @throws IllegalAccessException  ignored
     */
    public void setup(Object ob) throws NoSuchFieldException, IllegalAccessException {
        Field field = ob.getClass().getDeclaredField(FUNC_REFS);
        field.setAccessible(true);
        field.set(ob, funcRefs.toArray(new ScriptContext.Func[0]));
    }
}
