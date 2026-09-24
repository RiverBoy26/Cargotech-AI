FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY assets /usr/share/nginx/html/assets
COPY mocks /usr/share/nginx/html/mocks
COPY pages /usr/share/nginx/html/pages
COPY scripts /usr/share/nginx/html/scripts
COPY styles /usr/share/nginx/html/styles
EXPOSE 80
