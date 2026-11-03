/**
 * customAttributesTab.js
 *
 * Loaded as an external static asset (/assets/plugin/custom-attributes/customAttributesTab.js)
 * so it is covered by the CSP 'self' rule.
 *
 * Data arrives in the page, never from the user:
 *   #ca-container[data-scope, data-id]  scope is 'instance' or 'server'; the id is the only identifier sent to the server
 *   #ca-all-attrs-data                JSON of every VM attribute: key, name, value, type
 *
 * API routes: /plugin/customAttributes/setAttr (session cookie, CSRF token, X-Requested-With).
 * Clearing a value is a save with an empty value.
 */
(function () {
  'use strict';

  // The tab HTML is inserted after DOMContentLoaded of the outer page, so wait for #ca-container.
  function init() {
    var container = document.getElementById('ca-container');
    if (!container) return;

    var OBJECT_ID   = container.getAttribute('data-id') || '';
    var ID_PARAM    = container.getAttribute('data-scope') === 'server' ? 'serverId' : 'instanceId';
    var BASE_URL    = '/plugin/customAttributes';
    var usePost     = true;
    var DYNAMIC     = container.getAttribute('data-dynamic') === 'true';

    var currentTarget = '';    // '' (nothing picked), a server id, or 'all'; only used when DYNAMIC

    var allAttrs = [];
    var originalValues = {};   // key -> value as loaded
    var modifiedValues = {};   // key -> edited value
    var newRows = [];          // [{ name, value, type }]

    function setAttrs(list) {
      allAttrs = list || [];
      originalValues = {};
      allAttrs.forEach(function (attr) {
        originalValues[attr.key] = attr.value || '';
      });
    }

    try {
      var dataEl = document.getElementById('ca-all-attrs-data');
      setAttrs(dataEl ? JSON.parse(dataEl.textContent || '[]') : []);
    } catch (e) {
      setAttrs([]);
    }

    var editBtn = document.getElementById('ca-open-edit-btn');
    if (editBtn) editBtn.addEventListener('click', openEditModal);

    var vmSelect = document.getElementById('ca-vm-select');
    if (vmSelect) vmSelect.addEventListener('change', function () { selectTarget(vmSelect.value); });

    var closeBtn = document.getElementById('ca-modal-close');
    if (closeBtn) closeBtn.addEventListener('click', closeModal);

    var cancelBtn = document.getElementById('ca-cancel-btn');
    if (cancelBtn) cancelBtn.addEventListener('click', closeModal);

    var saveBtn = document.getElementById('ca-save-btn');
    if (saveBtn) saveBtn.addEventListener('click', saveAllChanges);

    var addRowBtn = document.getElementById('ca-add-row-btn');
    if (addRowBtn) addRowBtn.addEventListener('click', addNewRow);

    var modalOverlay = document.getElementById('ca-edit-modal-overlay');
    if (modalOverlay) {
      modalOverlay.addEventListener('click', function (e) {
        if (e.target === this) closeModal();
      });
    }

    var newAttrName  = document.getElementById('ca-new-attr-name');
    var newAttrValue = document.getElementById('ca-new-attr-value');
    if (newAttrName)  newAttrName.addEventListener('keypress',  function (e) { if (e.key === 'Enter') addNewRow(); });
    if (newAttrValue) newAttrValue.addEventListener('keypress', function (e) { if (e.key === 'Enter') addNewRow(); });

    // -----------------------------------------------------------------------
    // VM picker (instance with several VMs): data comes from the vmData route
    // -----------------------------------------------------------------------
    function selectTarget(value) {
      currentTarget = value;
      modifiedValues = {};
      newRows = [];
      if (editBtn) editBtn.disabled = true;
      setAttrs([]);

      var note = document.getElementById('ca-select-note');
      if (!value) {
        if (note) note.textContent = '';
        renderCard('Select a VM to view its custom attributes.');
        return;
      }
      if (note) {
        if (value === 'all') {
          var primary = vmSelect.getAttribute('data-primary-name') || '';
          note.textContent = 'Showing the attributes of the primary VM ' + primary + '. Changes apply to all VMs.';
        } else {
          note.textContent = 'Changes apply to ' + vmSelect.options[vmSelect.selectedIndex].text + ' only.';
        }
      }
      loadVmData();
    }

    function loadVmData() {
      var asked = currentTarget;
      renderCard('Loading...');
      pluginCall('vmData', {})
        .then(function (res) {
          if (asked !== currentTarget) return;   // another VM was picked meanwhile
          if (!res || res.success === false) {
            renderCard((res && res.msg) || 'The attributes could not be loaded.', true);
            return;
          }
          setAttrs(res.attributes);
          var warn = document.getElementById('ca-cert-warning');
          if (warn && res.certUntrusted) warn.style.display = '';
          renderCard();
          if (editBtn) editBtn.disabled = false;
        })
        .catch(function (err) {
          if (asked !== currentTarget) return;
          renderCard('Request failed: ' + (err && err.message ? err.message : err), true);
        });
    }

    function renderCard(message, isError) {
      var card = document.getElementById('ca-status-card');
      if (!card) return;
      card.textContent = '';
      var withValues = allAttrs.filter(function (a) { return a.value; });
      if (message || withValues.length === 0) {
        var empty = document.createElement('div');
        empty.className = isError ? 'ca-error-banner' : 'ca-empty';
        empty.textContent = message || 'No custom attributes assigned.';
        card.appendChild(empty);
        return;
      }
      withValues.forEach(function (a) {
        var row = document.createElement('div');
        row.className = 'ca-attr-row';
        var n = document.createElement('div');
        n.className = 'ca-attr-name';
        n.textContent = a.name;
        var v = document.createElement('div');
        v.className = 'ca-attr-val';
        v.textContent = a.value;
        row.appendChild(n);
        row.appendChild(v);
        card.appendChild(row);
      });
    }

    // -----------------------------------------------------------------------
    // Modal open / close
    // -----------------------------------------------------------------------
    function openEditModal() {
      if (DYNAMIC && !currentTarget) return;
      modifiedValues = {};
      newRows = [];
      populateList();
      showOverlay('ca-edit-modal-overlay');
      clearStatus();
      updateSaveButton();
    }

    function hasChanges() {
      return Object.keys(modifiedValues).length > 0 || newRows.length > 0;
    }

    function closeModal() {
      if (hasChanges() && !confirm('Discard unsaved changes?')) return;
      hideOverlay('ca-edit-modal-overlay');
      modifiedValues = {};
      newRows = [];
    }

    // -----------------------------------------------------------------------
    // Attribute list
    // -----------------------------------------------------------------------
    function populateList() {
      var listEl = document.getElementById('ca-attr-list');
      if (!listEl) return;
      listEl.textContent = '';

      if (allAttrs.length === 0) {
        var empty = document.createElement('div');
        empty.className = 'ca-attr-empty';
        empty.textContent = 'No custom attributes defined in vCenter.';
        listEl.appendChild(empty);
        return;
      }

      allAttrs.forEach(function (attr) {
        listEl.appendChild(createListItem(attr.key, attr.name, attr.value || '', attr.type, false));
      });
    }

    function createListItem(key, name, value, type, isNew) {
      var row = document.createElement('div');
      row.className = 'ca-attr-item' + (isNew ? ' ca-new' : '');

      var nameEl = document.createElement('div');
      nameEl.className = 'ca-attr-name';
      nameEl.textContent = name;
      nameEl.title = name;

      var wrap = document.createElement('div');
      wrap.className = 'ca-attr-value-wrapper';
      var input = document.createElement('input');
      input.type = 'text';
      input.className = 'ca-value-input';
      input.value = value;
      input.addEventListener('input', function () {
        handleValueChange(row, key, isNew, name, input.value);
      });
      wrap.appendChild(input);

      var typeEl = document.createElement('div');
      typeEl.className = 'ca-attr-type';
      typeEl.textContent = (type === 'Global') ? 'Global' : 'Virtual Machine';

      row.appendChild(nameEl);
      row.appendChild(wrap);
      row.appendChild(typeEl);
      return row;
    }

    function handleValueChange(row, key, isNew, name, newValue) {
      if (isNew) {
        newRows.forEach(function (r) { if (r.name === name) r.value = newValue; });
      } else if (newValue !== (originalValues[key] || '')) {
        modifiedValues[key] = newValue;
        row.classList.add('ca-modified');
      } else {
        delete modifiedValues[key];
        row.classList.remove('ca-modified');
      }
      updateSaveButton();
    }

    function updateSaveButton() {
      var btn = document.getElementById('ca-save-btn');
      if (!btn) return;
      if (hasChanges()) btn.classList.add('active'); else btn.classList.remove('active');
    }

    // -----------------------------------------------------------------------
    // Add a new attribute row (saved together with the other changes)
    // -----------------------------------------------------------------------
    function sameName(a, b) {
      return a.replace(/\s/g, '').toLowerCase() === b.replace(/\s/g, '').toLowerCase();
    }

    function addNewRow() {
      var nameInput  = document.getElementById('ca-new-attr-name');
      var valueInput = document.getElementById('ca-new-attr-value');
      var typeSelect = document.getElementById('ca-new-attr-type');
      if (!nameInput || !valueInput || !typeSelect) return;

      var name  = nameInput.value.trim();
      var value = valueInput.value.trim();
      var type  = typeSelect.value;

      if (!name) {
        showStatus('Please enter an attribute name.', 'error');
        nameInput.focus();
        return;
      }

      var exists = allAttrs.some(function (a) { return sameName(a.name, name); }) ||
                   newRows.some(function (r) { return sameName(r.name, name); });
      if (exists) {
        showStatus('Attribute "' + name + '" already exists.', 'error');
        return;
      }

      var listEl = document.getElementById('ca-attr-list');
      var emptyEl = listEl.querySelector('.ca-attr-empty');
      if (emptyEl) listEl.removeChild(emptyEl);
      listEl.insertBefore(createListItem('new-' + newRows.length, name, value, type, true), listEl.firstChild);
      newRows.push({ name: name, value: value, type: type });

      nameInput.value = '';
      valueInput.value = '';
      typeSelect.selectedIndex = 0;
      nameInput.focus();

      showStatus('Attribute "' + name + '" added. Click SAVE to commit.', 'info');
      updateSaveButton();
    }

    // -----------------------------------------------------------------------
    // Save every change, one request after another
    // -----------------------------------------------------------------------
    function saveAllChanges() {
      var btn = document.getElementById('ca-save-btn');
      if (!btn || !btn.classList.contains('active')) return;

      var updates = [];
      allAttrs.forEach(function (attr) {
        if (Object.prototype.hasOwnProperty.call(modifiedValues, attr.key)) {
          updates.push({ name: attr.name, value: modifiedValues[attr.key], type: attr.type });
        }
      });
      newRows.forEach(function (r) { updates.push({ name: r.name, value: r.value, type: r.type }); });

      if (updates.length === 0) {
        showStatus('No changes to save.', 'info');
        return;
      }

      btn.classList.remove('active');
      btn.textContent = 'SAVING...';
      showStatus('Saving ' + updates.length + ' change(s)...', 'info');

      var failures = [];
      var chain = Promise.resolve();
      updates.forEach(function (u) {
        chain = chain.then(function () {
          var params = { name: u.name, value: u.value, type: u.type };
          params[ID_PARAM] = OBJECT_ID;
          return pluginCall('setAttr', params)
            .then(function (res) {
              if (!res || res.success === false) {
                failures.push(u.name + ': ' + ((res && res.msg) || 'Unknown error'));
              }
            });
        });
      });

      chain
        .then(function () {
          if (failures.length > 0) {
            showStatus('Some attributes failed: ' + failures.join('; '), 'error');
            btn.textContent = 'SAVE';
            updateSaveButton();
          } else {
            showStatus('All changes saved.', 'success');
            if (DYNAMIC) {
              setTimeout(function () {
                hideOverlay('ca-edit-modal-overlay');
                modifiedValues = {};
                newRows = [];
                btn.textContent = 'SAVE';
                loadVmData();
              }, 800);
            } else {
              setTimeout(function () { location.reload(); }, 1000);
            }
          }
        })
        .catch(function (err) {
          showStatus('Request failed: ' + (err && err.message ? err.message : err), 'error');
          btn.textContent = 'SAVE';
          updateSaveButton();
        });
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    /**
     * POST /plugin/customAttributes/{action} with a form-encoded body.
     * Every call carries X-Requested-With so the server can reject requests that did not
     * originated from this page. Only the instance or server id, attribute name, value and type are sent;
     * the server resolves the VMs and the vCenter itself.
     * If the platform does not route POST for plugin controllers (HTTP 404/405) the same
     * call is repeated once as GET, which the server accepts only with the same header.
     */
    function pluginCall(action, params) {
      params = Object.assign({}, params);
      params[ID_PARAM] = OBJECT_ID;
      if (DYNAMIC) params.target = currentTarget;
      var meta = function (n) { var e = document.querySelector('meta[name="' + n + '"]'); return e ? e.getAttribute('content') : ''; };
      var csrfToken = meta('_csrf');
      var csrfHeader = meta('_csrf_header') || 'X-CSRF-TOKEN';
      var csrfParam = meta('_csrf_parameter') || '_csrf';
      var postParams = Object.assign({}, params);
      if (csrfToken) postParams[csrfParam] = csrfToken;
      var body = new URLSearchParams(params).toString();
      var postBody = new URLSearchParams(postParams).toString();
      var baseHeaders = { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' };
      var postHeaders = Object.assign({ 'Content-Type': 'application/x-www-form-urlencoded' }, baseHeaders);
      if (csrfToken) postHeaders[csrfHeader] = csrfToken;

      function parse(r) {
        return r.text().then(function (t) {
          var json = null;
          try { json = JSON.parse(t); } catch (e) { json = null; }
          return { status: r.status, json: json };
        });
      }

      function viaGet() {
        usePost = false;
        return fetch(BASE_URL + '/' + action + '?' + body, {
          method     : 'GET',
          headers    : baseHeaders,
          credentials: 'same-origin'
        }).then(parse).then(function (res2) {
          if (res2.json) return res2.json;
          throw new Error('Unexpected response (HTTP ' + res2.status + ')');
        });
      }

      if (!usePost) return viaGet();

      return fetch(BASE_URL + '/' + action, {
        method     : 'POST',
        headers    : postHeaders,
        credentials: 'same-origin',
        body       : postBody
      }).then(parse).then(function (res) {
        if (res.json) return res.json;
        // Morpheus answers a POST without its CSRF token with a redirect to /error/invalid-csrf (403).
        if (res.status === 403 || res.status === 404 || res.status === 405) return viaGet();
        throw new Error('Unexpected response (HTTP ' + res.status + ')');
      });
    }

    function showOverlay(id) {
      var el = document.getElementById(id);
      if (el) el.classList.add('active');
    }

    function hideOverlay(id) {
      var el = document.getElementById(id);
      if (el) el.classList.remove('active');
    }

    function showStatus(msg, type) {
      var el = document.getElementById('ca-status-msg');
      if (!el) return;
      el.textContent = msg;
      el.className = 'ca-status-msg ' + type;
    }

    function clearStatus() {
      var el = document.getElementById('ca-status-msg');
      if (!el) return;
      el.textContent = '';
      el.className = 'ca-status-msg';
    }
  }

  if (document.getElementById('ca-container')) {
    init();
  } else {
    var observer = new MutationObserver(function (mutations, obs) {
      if (document.getElementById('ca-container')) {
        obs.disconnect();
        init();
      }
    });
    observer.observe(document.body, { childList: true, subtree: true });
  }
})();
