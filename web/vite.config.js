import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { resolve } from 'path';
import AutoImport from 'unplugin-auto-import/vite';
import Components from 'unplugin-vue-components/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
// Vite 配置：开发服务器通过 /api 代理到后端 /aifp，避免跨域并保持 baseURL 统一
export default defineConfig({
    plugins: [
        vue(),
        // Element Plus 按需自动导入（API）
        AutoImport({
            resolvers: [ElementPlusResolver()]
        }),
        // Element Plus 按需自动注册（组件）
        Components({
            resolvers: [ElementPlusResolver()]
        })
    ],
    resolve: {
        alias: {
            '@': resolve(__dirname, 'src')
        }
    },
    server: {
        port: 5173,
        open: true,
        proxy: {
            // /api -> http://localhost:8080/aifp，前端统一使用 /api 作为 baseURL
            '/api': {
                target: 'http://localhost:8080/aifp',
                changeOrigin: true,
                rewrite: function (path) { return path.replace(/^\/api/, ''); }
            }
        }
    },
    css: {
        preprocessorOptions: {
            scss: {
                // 关闭 Element Plus 主题变量警告
                silenceDeprecations: ['legacy-js-api']
            }
        }
    }
});
