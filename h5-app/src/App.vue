<script>
export default {
  onLaunch() {},
  onShow() {},
  onHide() {}
}
</script>

<style>
page {
  background-color: #F5F6F8;
  color: #1A1F2C;
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Hiragino Sans GB',
    'Microsoft YaHei', 'Helvetica Neue', sans-serif;
  -webkit-font-smoothing: antialiased;
}

view,
text,
input,
textarea,
button,
scroll-view {
  box-sizing: border-box;
}

button::after {
  border: none;
}

button {
  margin: 0;
  padding: 0;
  border-radius: 0;
  background: transparent;
  line-height: inherit;
}

button[loading]::before {
  margin-right: 10rpx;
}
</style>
