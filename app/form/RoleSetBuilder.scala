package form

import db.DbRef
import models.user.User
import models.user.role.UserRoleModel
import ore.permission.role.{Role, RoleCategory}

/**
  * Builds a set of [[UserRoleModel]]s based on input data.
  *
  * @tparam M RoleModel type
  */
trait RoleSetBuilder[M <: UserRoleModel] {

  /**
    * Returns the user IDs to use in building the set.
    *
    * @return User IDs
    */
  def users: List[DbRef[User]]

  /**
    * Returns the role names to use in building the set.
    *
    * @return Role names
    */
  def roles: List[String]

  /**
    * Builds the result set from the form data.
    *
    * @return Result set
    */
  def build(): Set[M] = users.zip(roles).map { case (userId, role) => newRole(userId, Role.withValue(role)) }.toSet.filter(userRole => {
      userRole.role.category.equals(RoleCategory.Project)
    })

  /**
    * Creates a new role for the specified user ID and role type.
    *
    * @param userId User ID
    * @param role   Role type
    * @return       New role
    */
  def newRole(userId: DbRef[User], role: Role): M
}
