import {createRouter, createWebHistory, type RouteRecordRaw} from 'vue-router'
import MainLayout from '@/layouts/MainLayout.vue'

/**
 * 路由表
 * - 顶层 MainLayout 承载企业后台布局（Header + Sidebar + Content）
 * - / 默认重定向到 /form/list
 * - HealthController 不生成路由
 */
const routes: RouteRecordRaw[] = [
    {
        path: '/',
        component: MainLayout,
        redirect: '/form/list',
        children: [
            {
                path: '/form/list',
                name: 'FormList',
                component: () => import('@/views/form/FormList.vue'),
                meta: {title: '表单管理', icon: 'Document'}
            },
            {
                path: '/form/create',
                name: 'FormCreate',
                component: () => import('@/views/form/FormCreate.vue'),
                meta: {title: '创建表单', icon: 'EditPen', hidden: true}
            },
            {
                path: '/form/:id',
                name: 'FormDetail',
                component: () => import('@/views/form/FormDetail.vue'),
                meta: {title: '表单详情', icon: 'View', hidden: true}
            },
            {
                path: '/file/upload',
                name: 'FileUpload',
                component: () => import('@/views/file/FileUpload.vue'),
                meta: {title: '文件上传', icon: 'UploadFilled'}
            },
            {
                path: '/fill/index',
                name: 'AiFill',
                component: () => import('@/views/fill/AiFill.vue'),
                meta: {title: 'AI自动填报', icon: 'MagicStick'}
            }
        ]
    },
    {
        path: '/:pathMatch(.*)*',
        name: 'NotFound',
        component: () => import('@/views/NotFound.vue'),
        meta: {title: '页面不存在', hidden: true}
    }
]

const router = createRouter({
    history: createWebHistory(),
    routes
})

// 全局前置守卫：设置页面标题
router.beforeEach((to, _from, next) => {
    const title = (to.meta?.title as string) || ''
    document.title = title ? `${title} - AI文件自动填报系统` : 'AI文件自动填报系统'
    next()
})

export default router
