/** 登录：后端返回角色，前端仅做路由与菜单的可见性控制 */
export interface LoginResult {
  token: string
  role: 'OPERATOR' | 'INVESTOR' | 'ADMIN'
  nickname: string
}
