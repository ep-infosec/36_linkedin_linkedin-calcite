/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.rel.type;

import org.apache.calcite.linq4j.tree.Primitive;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.sql.SqlCollation;
import org.apache.calcite.sql.type.JavaToSqlTypeConversionRules;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.util.Util;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Abstract base for implementations of {@link RelDataTypeFactory}.
 */
public abstract class RelDataTypeFactoryImpl implements RelDataTypeFactory {
  //~ Instance fields --------------------------------------------------------

  /**
   * Global cache for Key to RelDataType. Uses soft values to allow GC.
   */
  private static final LoadingCache<Key, RelDataType> KEY2TYPE_CACHE;
  private static final long KEY2TYPE_CACHE_MAX_SIZE;
  static {
    CacheBuilder<Object, Object> cacheBuilder = CacheBuilder.newBuilder();
    KEY2TYPE_CACHE_MAX_SIZE = Hook.REL_DATA_TYPE_CACHE_SIZE.get(-1L);
    if (KEY2TYPE_CACHE_MAX_SIZE != -1L) {
      cacheBuilder.maximumSize(KEY2TYPE_CACHE_MAX_SIZE);
    }
    KEY2TYPE_CACHE = cacheBuilder
        .softValues()
        .build(CacheLoader.from(RelDataTypeFactoryImpl::keyToType));
  }

  /**
   * Global cache for RelDataType.
   */
  private static final Interner<RelDataType> DATATYPE_CACHE =
      Interners.newWeakInterner();

  private static RelDataType keyToType(@Nonnull Key key) {
    final ImmutableList.Builder<RelDataTypeField> list =
        ImmutableList.builder();
    for (int i = 0; i < key.names.size(); i++) {
      list.add(
          new RelDataTypeFieldImpl(
              key.names.get(i), i, key.types.get(i)));
    }
    return new RelRecordType(key.kind, list.build(), key.nullable);
  }

  private static final Map<Class, RelDataTypeFamily> CLASS_FAMILIES =
      ImmutableMap.<Class, RelDataTypeFamily>builder()
          .put(String.class, SqlTypeFamily.CHARACTER)
          .put(byte[].class, SqlTypeFamily.BINARY)
          .put(boolean.class, SqlTypeFamily.BOOLEAN)
          .put(Boolean.class, SqlTypeFamily.BOOLEAN)
          .put(char.class, SqlTypeFamily.NUMERIC)
          .put(Character.class, SqlTypeFamily.NUMERIC)
          .put(short.class, SqlTypeFamily.NUMERIC)
          .put(Short.class, SqlTypeFamily.NUMERIC)
          .put(int.class, SqlTypeFamily.NUMERIC)
          .put(Integer.class, SqlTypeFamily.NUMERIC)
          .put(long.class, SqlTypeFamily.NUMERIC)
          .put(Long.class, SqlTypeFamily.NUMERIC)
          .put(float.class, SqlTypeFamily.APPROXIMATE_NUMERIC)
          .put(Float.class, SqlTypeFamily.APPROXIMATE_NUMERIC)
          .put(double.class, SqlTypeFamily.APPROXIMATE_NUMERIC)
          .put(Double.class, SqlTypeFamily.APPROXIMATE_NUMERIC)
          .put(java.sql.Date.class, SqlTypeFamily.DATE)
          .put(Time.class, SqlTypeFamily.TIME)
          .put(Timestamp.class, SqlTypeFamily.TIMESTAMP)
          .build();

  protected final RelDataTypeSystem typeSystem;

  //~ Constructors -----------------------------------------------------------

  /** Creates a type factory. */
  protected RelDataTypeFactoryImpl(RelDataTypeSystem typeSystem) {
    this.typeSystem = Objects.requireNonNull(typeSystem);
  }

  //~ Methods ----------------------------------------------------------------

  // return the size limit of KEY2TYPE_CACHE, -1L means no size limit.
  public static long getKey2typeCacheMaxSize() {
    return KEY2TYPE_CACHE_MAX_SIZE;
  }

  public RelDataTypeSystem getTypeSystem() {
    return typeSystem;
  }

  // implement RelDataTypeFactory
  public RelDataType createJavaType(Class clazz) {
    final JavaType javaType =
        clazz == String.class
            ? new JavaType(clazz, true, getDefaultCharset(),
                SqlCollation.IMPLICIT)
            : new JavaType(clazz);
    return canonize(javaType);
  }

  // implement RelDataTypeFactory
  public RelDataType createJoinType(RelDataType... types) {
    assert types != null;
    assert types.length >= 1;
    final List<RelDataType> flattenedTypes = new ArrayList<>();
    getTypeList(ImmutableList.copyOf(types), flattenedTypes);
    return canonize(
        new RelCrossType(flattenedTypes, getFieldList(flattenedTypes)));
  }

  public RelDataType createStructType(
      final List<RelDataType> typeList,
      final List<String> fieldNameList) {
    return createStructType(StructKind.FULLY_QUALIFIED, typeList,
        fieldNameList);
  }

  public RelDataType createStructType(StructKind kind,
      final List<RelDataType> typeList,
      final List<String> fieldNameList) {
    return createStructType(kind, typeList,
        fieldNameList, false);
  }

  private RelDataType createStructType(StructKind kind,
      final List<RelDataType> typeList,
      final List<String> fieldNameList,
      final boolean nullable) {
    assert typeList.size() == fieldNameList.size();
    return canonize(kind, fieldNameList, typeList, nullable);
  }

  @SuppressWarnings("deprecation")
  public RelDataType createStructType(
      final RelDataTypeFactory.FieldInfo fieldInfo) {
    return canonize(StructKind.FULLY_QUALIFIED,
        new AbstractList<String>() {
          @Override public String get(int index) {
            return fieldInfo.getFieldName(index);
          }

          @Override public int size() {
            return fieldInfo.getFieldCount();
          }
        },
        new AbstractList<RelDataType>() {
          @Override public RelDataType get(int index) {
            return fieldInfo.getFieldType(index);
          }

          @Override public int size() {
            return fieldInfo.getFieldCount();
          }
        });
  }

  public final RelDataType createStructType(
      final List<? extends Map.Entry<String, RelDataType>> fieldList) {
    return createStructType(fieldList, false);
  }

  private RelDataType createStructType(
      final List<? extends Map.Entry<String, RelDataType>> fieldList, boolean nullable) {
    return canonize(StructKind.FULLY_QUALIFIED,
        new AbstractList<String>() {
          @Override public String get(int index) {
            return fieldList.get(index).getKey();
          }

          @Override public int size() {
            return fieldList.size();
          }
        },
        new AbstractList<RelDataType>() {
          @Override public RelDataType get(int index) {
            return fieldList.get(index).getValue();
          }

          @Override public int size() {
            return fieldList.size();
          }
        }, nullable);
  }

  public RelDataType leastRestrictive(List<RelDataType> types) {
    assert types != null;
    assert types.size() >= 1;
    RelDataType type0 = types.get(0);
    if (type0.isStruct()) {
      return leastRestrictiveStructuredType(types);
    }
    return null;
  }

  protected RelDataType leastRestrictiveStructuredType(
      final List<RelDataType> types) {
    final RelDataType type0 = types.get(0);
    final int fieldCount = type0.getFieldCount();

    // precheck that all types are structs with same number of fields
    // and register desired nullability for the result
    boolean isNullable = false;
    for (RelDataType type : types) {
      if (!type.isStruct()) {
        return null;
      }
      if (type.getFieldList().size() != fieldCount) {
        return null;
      }
      isNullable |= type.isNullable();
    }

    // recursively compute column-wise least restrictive
    final Builder builder = builder();
    for (int j = 0; j < fieldCount; ++j) {
      // REVIEW jvs 22-Jan-2004:  Always use the field name from the
      // first type?
      final int k = j;
      builder.add(
          type0.getFieldList().get(j).getName(),
          leastRestrictive(
              new AbstractList<RelDataType>() {
                public RelDataType get(int index) {
                  return types.get(index).getFieldList().get(k).getType();
                }

                public int size() {
                  return types.size();
                }
              }));
    }
    return createTypeWithNullability(builder.build(), isNullable);
  }

  // copy a non-record type, setting nullability
  private RelDataType copySimpleType(
      RelDataType type,
      boolean nullable) {
    if (type instanceof JavaType) {
      JavaType javaType = (JavaType) type;
      if (SqlTypeUtil.inCharFamily(javaType)) {
        return new JavaType(
            javaType.clazz,
            nullable,
            javaType.charset,
            javaType.collation);
      } else {
        return new JavaType(
            nullable
                ? Primitive.box(javaType.clazz)
                : Primitive.unbox(javaType.clazz),
            nullable);
      }
    } else {
      // REVIEW: RelCrossType if it stays around; otherwise get rid of
      // this comment
      return type;
    }
  }

  // recursively copy a record type
  private RelDataType copyRecordType(
      final RelRecordType type,
      final boolean ignoreNullable,
      final boolean nullable) {
    // For flattening and outer joins, it is desirable to change
    // the nullability of the individual fields.
    return createStructType(type.getStructKind(),
        new AbstractList<RelDataType>() {
          @Override public RelDataType get(int index) {
            RelDataType fieldType =
                type.getFieldList().get(index).getType();
            if (ignoreNullable) {
              return copyType(fieldType);
            } else {
              return createTypeWithNullability(fieldType, nullable);
            }
          }

          @Override public int size() {
            return type.getFieldCount();
          }
        },
        type.getFieldNames(), nullable);
  }

  // implement RelDataTypeFactory
  public RelDataType copyType(RelDataType type) {
    return createTypeWithNullability(type, type.isNullable());
  }

  // implement RelDataTypeFactory
  public RelDataType createTypeWithNullability(
      final RelDataType type,
      final boolean nullable) {
    Objects.requireNonNull(type);
    RelDataType newType;
    if (type.isNullable() == nullable) {
      newType = type;
    } else if (type instanceof RelRecordType) {
      // REVIEW: angel 18-Aug-2005 dtbug 336 workaround
      // Changed to ignore nullable parameter if nullable is false since
      // copyRecordType implementation is doubtful
      // - If nullable -> Do a deep copy, setting all fields of the record type
      // to be nullable regardless of initial nullability.
      // - If not nullable -> Do a deep copy, setting not nullable at top RelRecordType
      // level only, keeping its fields' nullability as before.
      // According to the SQL standard, nullability for struct types can be defined only for
      // columns, which translates to top level structs. Nested struct attributes are always
      // nullable, so in principle we could always set the nested attributes to be nullable.
      // However, this might create regressions so we will not do it and we will keep previous
      // behavior.
      newType = copyRecordType((RelRecordType) type, !nullable,  nullable);
    } else {
      newType = copySimpleType(type, nullable);
    }
    return canonize(newType);
  }

  /**
   * Registers a type, or returns the existing type if it is already
   * registered.
   *
   * @throws NullPointerException if type is null
   */
  protected RelDataType canonize(final RelDataType type) {
    return DATATYPE_CACHE.intern(type);
  }

  /**
   * Looks up a type using a temporary key, and if not present, creates
   * a permanent key and type.
   *
   * <p>This approach allows us to use a cheap temporary key. A permanent
   * key is more expensive, because it must be immutable and not hold
   * references into other data structures.</p>
   */
  protected RelDataType canonize(final StructKind kind,
      final List<String> names,
      final List<RelDataType> types,
      final boolean nullable) {
    final RelDataType type = KEY2TYPE_CACHE.getIfPresent(
        new Key(kind, names, types, nullable));
    if (type != null) {
      return type;
    }
    final ImmutableList<String> names2 = ImmutableList.copyOf(names);
    final ImmutableList<RelDataType> types2 = ImmutableList.copyOf(types);
    return KEY2TYPE_CACHE.getUnchecked(new Key(kind, names2, types2, nullable));
  }

  protected RelDataType canonize(final StructKind kind,
      final List<String> names,
      final List<RelDataType> types) {
    return canonize(kind, names, types, false);
  }

  /**
   * Returns a list of the fields in a list of types.
   */
  private static List<RelDataTypeField> getFieldList(List<RelDataType> types) {
    final List<RelDataTypeField> fieldList = new ArrayList<>();
    for (RelDataType type : types) {
      addFields(type, fieldList);
    }
    return fieldList;
  }

  /**
   * Returns a list of all atomic types in a list.
   */
  private static void getTypeList(
      ImmutableList<RelDataType> inTypes,
      List<RelDataType> flatTypes) {
    for (RelDataType inType : inTypes) {
      if (inType instanceof RelCrossType) {
        getTypeList(((RelCrossType) inType).types, flatTypes);
      } else {
        flatTypes.add(inType);
      }
    }
  }

  /**
   * Adds all fields in <code>type</code> to <code>fieldList</code>,
   * renumbering the fields (if necessary) to ensure that their index
   * matches their position in the list.
   */
  private static void addFields(
      RelDataType type,
      List<RelDataTypeField> fieldList) {
    if (type instanceof RelCrossType) {
      final RelCrossType crossType = (RelCrossType) type;
      for (RelDataType type1 : crossType.types) {
        addFields(type1, fieldList);
      }
    } else {
      List<RelDataTypeField> fields = type.getFieldList();
      for (RelDataTypeField field : fields) {
        if (field.getIndex() != fieldList.size()) {
          field = new RelDataTypeFieldImpl(field.getName(), fieldList.size(),
              field.getType());
        }
        fieldList.add(field);
      }
    }
  }

  public static boolean isJavaType(RelDataType t) {
    return t instanceof JavaType;
  }

  private List<RelDataTypeFieldImpl> fieldsOf(Class clazz) {
    final List<RelDataTypeFieldImpl> list = new ArrayList<>();
    for (Field field : clazz.getFields()) {
      if (Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      list.add(
          new RelDataTypeFieldImpl(
              field.getName(),
              list.size(),
              createJavaType(field.getType())));
    }

    if (list.isEmpty()) {
      return null;
    }

    return list;
  }

  /**
   * Delegates to
   * {@link RelDataTypeSystem#deriveDecimalMultiplyType(RelDataTypeFactory, RelDataType, RelDataType)}
   * to get the return type for the operation.
   */
  @Deprecated
  public RelDataType createDecimalProduct(
      RelDataType type1,
      RelDataType type2) {
    return typeSystem.deriveDecimalMultiplyType(this, type1, type2);
  }

  /**
   * Delegates to
   * {@link RelDataTypeSystem#shouldUseDoubleMultiplication(RelDataTypeFactory, RelDataType, RelDataType)}
   * to get if double should be used for multiplication.
   */
  @Deprecated
  public boolean useDoubleMultiplication(
      RelDataType type1,
      RelDataType type2) {
    return typeSystem.shouldUseDoubleMultiplication(this, type1, type2);
  }

  /**
   * Delegates to
   * {@link RelDataTypeSystem#deriveDecimalDivideType(RelDataTypeFactory, RelDataType, RelDataType)}
   * to get the return type for the operation.
   */
  @Deprecated
  public RelDataType createDecimalQuotient(
      RelDataType type1,
      RelDataType type2) {
    return typeSystem.deriveDecimalDivideType(this, type1, type2);
  }

  public RelDataType decimalOf(RelDataType type) {
    // create decimal type and sync nullability
    return createTypeWithNullability(decimalOf2(type), type.isNullable());
  }

  /** Create decimal type equivalent with the given {@code type} while sans nullability. */
  private RelDataType decimalOf2(RelDataType type) {
    assert SqlTypeUtil.isNumeric(type) || SqlTypeUtil.isNull(type);
    SqlTypeName typeName = type.getSqlTypeName();
    assert typeName != null;
    switch (typeName) {
    case DECIMAL:
      return type;
    case TINYINT:
      return createSqlType(SqlTypeName.DECIMAL, 3, 0);
    case SMALLINT:
      return createSqlType(SqlTypeName.DECIMAL, 5, 0);
    case INTEGER:
      return createSqlType(SqlTypeName.DECIMAL, 10, 0);
    case BIGINT:
      // the default max precision is 19, so this is actually DECIMAL(19, 0)
      // but derived system can override the max precision/scale.
      return createSqlType(SqlTypeName.DECIMAL, 38, 0);
    case REAL:
      return createSqlType(SqlTypeName.DECIMAL, 14, 7);
    case FLOAT:
      return createSqlType(SqlTypeName.DECIMAL, 14, 7);
    case DOUBLE:
      // the default max precision is 19, so this is actually DECIMAL(19, 15)
      // but derived system can override the max precision/scale.
      return createSqlType(SqlTypeName.DECIMAL, 30, 15);
    default:
      // default precision and scale.
      return createSqlType(SqlTypeName.DECIMAL);
    }
  }

  public Charset getDefaultCharset() {
    return Util.getDefaultCharset();
  }

  @SuppressWarnings("deprecation")
  public FieldInfoBuilder builder() {
    return new FieldInfoBuilder(this);
  }

  //~ Inner Classes ----------------------------------------------------------

  // TODO jvs 13-Dec-2004:  move to OJTypeFactoryImpl?

  /**
   * Type which is based upon a Java class.
   */
  public class JavaType extends RelDataTypeImpl {
    private final Class clazz;
    private final boolean nullable;
    private SqlCollation collation;
    private Charset charset;

    public JavaType(Class clazz) {
      this(clazz, !clazz.isPrimitive());
    }

    public JavaType(
        Class clazz,
        boolean nullable) {
      this(clazz, nullable, null, null);
    }

    public JavaType(
        Class clazz,
        boolean nullable,
        Charset charset,
        SqlCollation collation) {
      super(fieldsOf(clazz));
      this.clazz = clazz;
      this.nullable = nullable;
      assert (charset != null) == SqlTypeUtil.inCharFamily(this)
          : "Need to be a chartype";
      this.charset = charset;
      this.collation = collation;
      computeDigest();
    }

    public Class getJavaClass() {
      return clazz;
    }

    public boolean isNullable() {
      return nullable;
    }

    @Override public RelDataTypeFamily getFamily() {
      RelDataTypeFamily family = CLASS_FAMILIES.get(clazz);
      return family != null ? family : this;
    }

    protected void generateTypeString(StringBuilder sb, boolean withDetail) {
      sb.append("JavaType(");
      sb.append(clazz);
      sb.append(")");
    }

    public RelDataType getComponentType() {
      final Class componentType = clazz.getComponentType();
      if (componentType == null) {
        return null;
      } else {
        return createJavaType(componentType);
      }
    }

    public Charset getCharset() {
      return this.charset;
    }

    public SqlCollation getCollation() {
      return this.collation;
    }

    public SqlTypeName getSqlTypeName() {
      final SqlTypeName typeName =
          JavaToSqlTypeConversionRules.instance().lookup(clazz);
      if (typeName == null) {
        return SqlTypeName.OTHER;
      }
      return typeName;
    }
  }

  /** Key to the data type cache. */
  private static class Key {
    private final StructKind kind;
    private final List<String> names;
    private final List<RelDataType> types;
    private final boolean nullable;

    Key(StructKind kind, List<String> names, List<RelDataType> types, boolean nullable) {
      this.kind = kind;
      this.names = names;
      this.types = types;
      this.nullable = nullable;
    }

    @Override public int hashCode() {
      return Objects.hash(kind, names, types, nullable);
    }

    @Override public boolean equals(Object obj) {
      return obj == this
          || obj instanceof Key
          && kind == ((Key) obj).kind
          && names.equals(((Key) obj).names)
          && types.equals(((Key) obj).types)
          && nullable == ((Key) obj).nullable;
    }
  }
}

// End RelDataTypeFactoryImpl.java