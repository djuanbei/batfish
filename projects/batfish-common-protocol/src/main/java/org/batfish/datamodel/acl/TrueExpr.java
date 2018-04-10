package org.batfish.datamodel.acl;

import com.google.common.base.MoreObjects;
import java.util.Objects;

public class TrueExpr extends AclLineMatchExpr {
  private static final long serialVersionUID = 1L;
  public static final TrueExpr INSTANCE = new TrueExpr();

  private TrueExpr() {}

  @Override
  public <R> R accept(GenericAclLineMatchExprVisitor<R> visitor) {
    return visitor.visitTrueExpr(this);
  }

  @Override
  public int hashCode() {
    return Objects.hash((Boolean) true);
  }

  @Override
  protected boolean exprEquals(Object o) {
    return true;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(getClass()).toString();
  }
}