package com.qlangtech.tis.runtime.module.action;

import com.qlangtech.tis.runtime.module.misc.TokenizerType;
import com.qlangtech.tis.solrdao.ISchemaField;

/**
 * @author: baisui 百岁
 * @create: 2020-10-03 17:45
 **/
public class VisualType {

  public static final VisualType STRING_TYPE = new VisualType(ISchemaField.DEFAULT_STRING_TYPE_NAME, true);

  public final String type;

  // private final boolean ranageQueryAware;

  // 是否可分词
  private final boolean split;


  /**
   * @param type
   * @param split
   */
  public VisualType(String type, boolean split) {
    super();
    this.type = type;
    //this.ranageQueryAware = ranageQueryAware;
    this.split = split;
  }

  public TokenizerType[] getTokenerTypes() {
    if (this.split) {
      return TokenizerType.values();
    } else {
      return new TokenizerType[0];
    }
  }

  /**
   * 是否是可分词，庖丁等等
   *
   * @return
   */
  public boolean isSplit() {
    return split;
  }


  public String getType() {
    return type;
  }

  @Override
  public int hashCode() {
    return type.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (type.equals(((VisualType) obj).type)) {
      return true;
    }
    return false;
  }

}
